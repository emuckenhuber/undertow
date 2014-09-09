/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.server.handlers.proxy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.undertow.UndertowLogger;
import io.undertow.attribute.ExchangeAttribute;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientResponse;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.builder.HandlerBuilder;
import io.undertow.util.AttachmentKey;
import io.undertow.util.CopyOnWriteMap;
import io.undertow.util.HttpString;
import io.undertow.util.SameThreadExecutor;
import org.jboss.logging.Logger;
import org.xnio.IoUtils;
import org.xnio.XnioExecutor;

/**
 * An HTTP handler which proxies content to a remote server.
 * <p/>
 * This handler acts like a filter. The {@link ProxyClient} has a chance to decide if it
 * knows how to proxy the request. If it does then it will provide a connection that can
 * used to connect to the remote server, otherwise the next handler will be invoked and the
 * request will proceed as normal.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class ProxyHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(ProxyHandler.class);

    public static final String UTF_8 = "UTF-8";
    private final ProxyClient proxyClient;
    private final int maxRequestTime;

    private static final AttachmentKey<ProxyConnection> CONNECTION = AttachmentKey.create(ProxyConnection.class);

    /**
     * Map of additional headers to add to the request.
     */
    private final Map<HttpString, ExchangeAttribute> requestHeaders = new CopyOnWriteMap<>();

    private final HttpHandler next;
    private final boolean rewriteHostHeader;
    private final boolean reuseXForwarded;

    public ProxyHandler(ProxyClient proxyClient, int maxRequestTime, HttpHandler next) {
        this(proxyClient, maxRequestTime, next, false, false);
    }

  /**
   *
   * @param proxyClient the client to use to make the proxy call
   * @param maxRequestTime the maximum amount of time to allow the request to be processed
   * @param next the next handler in line
   * @param rewriteHostHeader should the HOST header be rewritten to use the target host of the call.
   * @param reuseXForwarded should any existing X-Forwarded-For header be used or should it be overwritten.
   */
    public ProxyHandler(ProxyClient proxyClient, int maxRequestTime, HttpHandler next, boolean rewriteHostHeader, boolean reuseXForwarded) {
        this.proxyClient = proxyClient;
        this.maxRequestTime = maxRequestTime;
        this.next = next;
        this.rewriteHostHeader = rewriteHostHeader;
        this.reuseXForwarded = reuseXForwarded;
    }


    public ProxyHandler(ProxyClient proxyClient, HttpHandler next) {
        this(proxyClient, -1, next);
    }

    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        final ProxyClient.ProxyTarget target = proxyClient.findTarget(exchange);
        if (target == null) {
            log.debugf("No proxy target for request to %s", exchange.getRequestURL());
            next.handleRequest(exchange);
            return;
        }
        final long timeout = maxRequestTime > 0 ? System.currentTimeMillis() + maxRequestTime : 0;
        final ProxyClientHandler clientHandler = new ProxyClientHandler(exchange, target, timeout);
        if (timeout > 0) {
            final XnioExecutor.Key key = exchange.getIoThread().executeAfter(new Runnable() {
                @Override
                public void run() {
                    clientHandler.cancel(exchange);
                }
            }, maxRequestTime, TimeUnit.MILLISECONDS);
            clientHandler.cancelKey = key;
        }
        exchange.addExchangeCompleteListener(clientHandler);
        exchange.dispatch(exchange.isInIoThread() ? SameThreadExecutor.INSTANCE : exchange.getIoThread(), clientHandler);
    }

    /**
     * Adds a request header to the outgoing request. If the header resolves to null or an empty string
     * it will not be added, however any existing header with the same name will be removed.
     *
     * @param header    The header name
     * @param attribute The header value attribute.
     * @return this
     */
    public ProxyHandler addRequestHeader(final HttpString header, final ExchangeAttribute attribute) {
        requestHeaders.put(header, attribute);
        return this;
    }

    /**
     * Adds a request header to the outgoing request. If the header resolves to null or an empty string
     * it will not be added, however any existing header with the same name will be removed.
     *
     * @param header The header name
     * @param value  The header value attribute.
     * @return this
     */
    public ProxyHandler addRequestHeader(final HttpString header, final String value) {
        requestHeaders.put(header, ExchangeAttributes.constant(value));
        return this;
    }

    /**
     * Adds a request header to the outgoing request. If the header resolves to null or an empty string
     * it will not be added, however any existing header with the same name will be removed.
     * <p/>
     * The attribute value will be parsed, and the resulting exchange attribute will be used to create the actual header
     * value.
     *
     * @param header    The header name
     * @param attribute The header value attribute.
     * @return this
     */
    public ProxyHandler addRequestHeader(final HttpString header, final String attribute, final ClassLoader classLoader) {
        requestHeaders.put(header, ExchangeAttributes.parser(classLoader).parse(attribute));
        return this;
    }

    /**
     * Removes a request header
     *
     * @param header the header
     * @return this
     */
    public ProxyHandler removeRequestHeader(final HttpString header) {
        requestHeaders.remove(header);
        return this;
    }

    public ProxyClient getProxyClient() {
        return proxyClient;
    }

    private final class ProxyClientHandler implements ProxyCallback<ProxyConnection>, ExchangeCompletionListener, Runnable {

        private int tries;

        private final long timeout;
        private final HttpServerExchange exchange;
        private final boolean emptyRequest;

        private XnioExecutor.Key cancelKey;
        private ProxyClient.ProxyTarget target;
        private ResponseCompletionHandle failedExchange;

        ProxyClientHandler(HttpServerExchange exchange, ProxyClient.ProxyTarget target, long timeout) {
            this.exchange = exchange;
            this.timeout = timeout;
            this.target = target;
            // Can only retry requests with an empty message body
            this.emptyRequest = exchange.isRequestComplete();
        }

        @Override
        public void run() {
            proxyClient.getConnection(target, exchange, this, -1, TimeUnit.MILLISECONDS);
        }

        @Override
        public void completed(final HttpServerExchange exchange, final ProxyConnection connection) {
            exchange.putAttachment(CONNECTION, connection);
            exchange.dispatch(SameThreadExecutor.INSTANCE, new ProxyAction(connection, this, exchange, requestHeaders, rewriteHostHeader, reuseXForwarded, emptyRequest));
        }

        @Override
        public void failed(final HttpServerExchange exchange) {
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
            } else {
                if (!retry(exchange)) {
                    couldNotResolveBackend(exchange);
                }
            }
        }

        @Override
        public void failed(HttpServerExchange exchange, ProxyConnection result) {
            // Propagate the failure to the node
            result.failed();
            IoUtils.safeClose(result.getConnection());
            if (exchange.isResponseStarted()) {
                // Connection failure, close both the exchange and proxy connection
                assert result == exchange.getAttachment(CONNECTION);
                IoUtils.safeClose(exchange.getConnection());
                // Cleanup
            } else {
                if (!retry(exchange)) {
                    if (failedExchange != null) {
                        failedExchange.complete();
                        failedExchange = null;
                    } else {
                        exchange.setResponseCode(500);
                        exchange.endExchange();
                    }
                }
            }
        }

        private boolean retry(final HttpServerExchange exchange) {
            final long time = System.currentTimeMillis();
            if (tries++ < target.getRequestErrorPolicy().getRetryCount()) {
                if (timeout > 0 && time > timeout) {
                    cancel(exchange);
                    return true; // Cancel takes care of sending a response
                } else {
                    target = proxyClient.findTarget(exchange);
                    if (target != null) {
                        final long remaining = timeout > 0 ? timeout - time : -1;
                        exchange.removeAttachment(CONNECTION); // Remove the connection information
                        proxyClient.getConnection(target, exchange, this, remaining, TimeUnit.MILLISECONDS);
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void responseComplete(ResponseCompletionHandle completionHandle, ProxyConnection clientConnection) {
            if (emptyRequest) {
                final ClientExchange clientExchange = completionHandle.getClientExchange();
                final ClientResponse response = clientExchange.getResponse();
                final ProxyRequestErrorPolicy retryPolicy = target.getRequestErrorPolicy();
                if (retryPolicy.retryNextNode(response)) {
                    final boolean canRetry = retryPolicy.canRetryRequest(exchange);
                    if (failedExchange != null) {
                        failedExchange.discard();
                        failedExchange = null;
                    }
                    clientConnection.failed();
                    failedExchange = completionHandle;
                    if (canRetry && retry(exchange)) {
                        return;
                    } else {
                        completionHandle.complete();
                    }
                } else {
                    completionHandle.complete(); // DONE
                }
            } else {
                completionHandle.complete();
            }
        }

        @Override
        public void queuedRequestFailed(HttpServerExchange exchange) {
            failed(exchange);
        }

        @Override
        public void couldNotResolveBackend(HttpServerExchange exchange) {
            if (exchange.isResponseStarted()) {
                IoUtils.safeClose(exchange.getConnection());
                if (failedExchange != null) {
                    failedExchange.discard();
                    failedExchange = null;
                }
            } else {
                if (failedExchange != null) {
                    failedExchange.complete();
                    failedExchange = null;
                } else {
                    exchange.setResponseCode(503);
                    exchange.endExchange();
                }
            }
        }

        void cancel(final HttpServerExchange exchange) {
            final ProxyConnection connectionAttachment = exchange.getAttachment(CONNECTION);
            if (connectionAttachment != null) {
                ClientConnection clientConnection = connectionAttachment.getConnection();
                UndertowLogger.REQUEST_LOGGER.timingOutRequest(clientConnection.getPeerAddress() + "" + exchange.getRequestURI());
                IoUtils.safeClose(clientConnection);
            } else {
                UndertowLogger.REQUEST_LOGGER.timingOutRequest(exchange.getRequestURI());
            }
            if (cancelKey != null) {
                cancelKey.remove();
                cancelKey = null;
            }
            couldNotResolveBackend(exchange);
        }

        @Override
        public void exchangeEvent(HttpServerExchange exchange, NextListener nextListener) {
            if (cancelKey != null) {
                cancelKey.remove();
                cancelKey = null;
            }
            if (failedExchange != null) {
                failedExchange.discard();
                failedExchange = null;
            }
            nextListener.proceed();
        }
    }

    public static class Builder implements HandlerBuilder {

        @Override
        public String name() {
            return "reverse-proxy";
        }

        @Override
        public Map<String, Class<?>> parameters() {
            return Collections.<String, Class<?>>singletonMap("hosts", String[].class);
        }

        @Override
        public Set<String> requiredParameters() {
            return Collections.singleton("hosts");
        }

        @Override
        public String defaultParameter() {
            return "hosts";
        }

        @Override
        public HandlerWrapper build(Map<String, Object> config) {
            String[] hosts = (String[]) config.get("hosts");
            List<URI> uris = new ArrayList<>();
            for(String host : hosts) {
                try {
                    uris.add(new URI(host));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            }
            return new Wrapper(uris);
        }

    }

    private static class Wrapper implements HandlerWrapper {

        private final List<URI> uris;

        private Wrapper(List<URI> uris) {
            this.uris = uris;
        }

        @Override
        public HttpHandler wrap(HttpHandler handler) {

            LoadBalancingProxyClient loadBalancingProxyClient = new LoadBalancingProxyClient();
            for(URI url : uris) {
                loadBalancingProxyClient.addHost(url);
            }
            return new ProxyHandler(loadBalancingProxyClient, handler);
        }
    }
}
