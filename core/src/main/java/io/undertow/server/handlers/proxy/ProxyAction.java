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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server.handlers.proxy;

import static io.undertow.server.handlers.proxy.ProxyHandler.UTF_8;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.CertificateEncodingException;
import javax.security.cert.X509Certificate;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.nio.channels.Channel;
import java.util.Deque;
import java.util.Map;

import io.undertow.UndertowLogger;
import io.undertow.attribute.ExchangeAttribute;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.client.ContinueNotification;
import io.undertow.client.ProxiedRequestAttachments;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.HttpUpgradeListener;
import io.undertow.server.RenegotiationRequiredException;
import io.undertow.server.SSLSessionInfo;
import io.undertow.server.protocol.http.HttpAttachments;
import io.undertow.server.protocol.http.HttpContinue;
import io.undertow.util.Attachable;
import io.undertow.util.Certificates;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoUtils;
import org.xnio.StreamConnection;
import org.xnio.channels.StreamSinkChannel;

/**
 *
 */
class ProxyAction implements Runnable {

    private final ProxyConnection clientConnection;
    private final HttpServerExchange exchange;
    private final ProxyCallback responseCallback;
    private final Map<HttpString, ExchangeAttribute> requestHeaders;
    private final boolean rewriteHostHeader;
    private final boolean reuseXForwarded;
    private final boolean emptyMessage;

    public ProxyAction(final ProxyConnection clientConnection, final ProxyCallback responseCallback,
                       final HttpServerExchange exchange, Map<HttpString, ExchangeAttribute> requestHeaders,
                       boolean rewriteHostHeader, boolean reuseXForwarded, boolean emptyMessage) {
        this.clientConnection = clientConnection;
        this.exchange = exchange;
        this.responseCallback = responseCallback;
        this.requestHeaders = requestHeaders;
        this.rewriteHostHeader = rewriteHostHeader;
        this.reuseXForwarded = reuseXForwarded;
        this.emptyMessage = emptyMessage;
    }

    @Override
    public void run() {
        final ClientRequest request = new ClientRequest();

        StringBuilder requestURI = new StringBuilder();
        try {
            if (exchange.getRelativePath().isEmpty()) {
                requestURI.append(encodeUrlPart(clientConnection.getTargetPath()));
            } else {
                if (clientConnection.getTargetPath().endsWith("/")) {
                    requestURI.append(clientConnection.getTargetPath().substring(0, clientConnection.getTargetPath().length() - 1));
                    requestURI.append(encodeUrlPart(exchange.getRelativePath()));
                } else {
                    requestURI = requestURI.append(clientConnection.getTargetPath());
                    requestURI.append(encodeUrlPart(exchange.getRelativePath()));
                }
            }
            boolean first = true;
            if (!exchange.getPathParameters().isEmpty()) {
                requestURI.append(';');
                for (Map.Entry<String, Deque<String>> entry : exchange.getPathParameters().entrySet()) {
                    if (first) {
                        first = false;
                    } else {
                        requestURI.append('&');
                    }
                    for (String val : entry.getValue()) {
                        requestURI.append(URLEncoder.encode(entry.getKey(), UTF_8));
                        requestURI.append('=');
                        requestURI.append(URLEncoder.encode(val, UTF_8));
                    }
                }
            }

            String qs = exchange.getQueryString();
            if (qs != null && !qs.isEmpty()) {
                requestURI.append('?');
                requestURI.append(qs);
            }
        } catch (UnsupportedEncodingException e) {
            //impossible
            exchange.setResponseCode(500);
            exchange.endExchange();
            return;
        }
        request.setPath(requestURI.toString())
                .setMethod(exchange.getRequestMethod());
        final HeaderMap inboundRequestHeaders = exchange.getRequestHeaders();
        final HeaderMap outboundRequestHeaders = request.getRequestHeaders();
        copyHeaders(outboundRequestHeaders, inboundRequestHeaders);

        if (!exchange.isPersistent()) {
            //just because the client side is non-persistent
            //we don't want to close the connection to the backend
            outboundRequestHeaders.put(Headers.CONNECTION, "keep-alive");
        }

        for (Map.Entry<HttpString, ExchangeAttribute> entry : requestHeaders.entrySet()) {
            String headerValue = entry.getValue().readAttribute(exchange);
            if (headerValue == null || headerValue.isEmpty()) {
                outboundRequestHeaders.remove(entry.getKey());
            } else {
                outboundRequestHeaders.put(entry.getKey(), headerValue.replace('\n', ' '));
            }
        }

        final SocketAddress address = exchange.getConnection().getPeerAddress();
        final String remoteHost = (address != null && address instanceof InetSocketAddress) ? ((InetSocketAddress) address).getHostString() : "localhost";
        request.putAttachment(ProxiedRequestAttachments.REMOTE_HOST, remoteHost);

        if (reuseXForwarded && request.getRequestHeaders().contains(Headers.X_FORWARDED_FOR)) {
            // We have an existing header so we shall simply append the host to the existing list
            final String current = request.getRequestHeaders().getFirst(Headers.X_FORWARDED_FOR);
            if (current == null || current.isEmpty()) {
                // It was empty so just add it
                request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, remoteHost);
            }
            else {
                // Add the new entry and reset the existing header
                request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, current + "," + remoteHost);
            }
        }
        else {
            // No existing header or not allowed to reuse the header so set it here
            request.getRequestHeaders().put(Headers.X_FORWARDED_FOR, remoteHost);
        }

        // Set the protocol header and attachment
        final String proto = exchange.getRequestScheme().equals("https") ? "https" : "http";
        request.getRequestHeaders().put(Headers.X_FORWARDED_PROTO, proto);
        request.putAttachment(ProxiedRequestAttachments.IS_SSL, proto.equals("https"));

        // Set the server name
        final String hostName = exchange.getHostName();
        request.getRequestHeaders().put(Headers.X_FORWARDED_HOST, hostName);
        request.putAttachment(ProxiedRequestAttachments.SERVER_NAME, hostName);

        // Set the port
        int port = exchange.getConnection().getLocalAddress(InetSocketAddress.class).getPort();
        request.getRequestHeaders().put(Headers.X_FORWARDED_PORT, port);
        request.putAttachment(ProxiedRequestAttachments.SERVER_PORT, port);

        SSLSessionInfo sslSessionInfo = exchange.getConnection().getSslSessionInfo();
        if (sslSessionInfo != null) {
            X509Certificate[] peerCertificates;
            try {
                peerCertificates = sslSessionInfo.getPeerCertificateChain();
                if (peerCertificates.length > 0) {
                    request.putAttachment(ProxiedRequestAttachments.SSL_CERT, Certificates.toPem(peerCertificates[0]));
                }
            } catch (SSLPeerUnverifiedException e) {
                //ignore
            } catch (CertificateEncodingException e) {
                //ignore
            } catch (RenegotiationRequiredException e) {
                //ignore
            }
            request.putAttachment(ProxiedRequestAttachments.SSL_CYPHER, sslSessionInfo.getCipherSuite());
            request.putAttachment(ProxiedRequestAttachments.SSL_SESSION_ID, sslSessionInfo.getSessionId());
        }

        if(rewriteHostHeader) {
            InetSocketAddress targetAddress = clientConnection.getConnection().getPeerAddress(InetSocketAddress.class);
            request.getRequestHeaders().put(Headers.HOST, targetAddress.getHostString() + ":" + targetAddress.getPort());
            request.getRequestHeaders().put(Headers.X_FORWARDED_HOST, exchange.getRequestHeaders().getFirst(Headers.HOST));
        }

        clientConnection.getConnection().sendRequest(request, new ClientCallback<ClientExchange>() {
            @Override
            public void completed(final ClientExchange result) {
                boolean requiresContinueResponse = HttpContinue.requiresContinueResponse(exchange);
                if (requiresContinueResponse) {
                    result.setContinueHandler(new ContinueNotification() {
                        @Override
                        public void handleContinue(final ClientExchange clientExchange) {
                            HttpContinue.sendContinueResponse(exchange, new IoCallback() {
                                @Override
                                public void onComplete(final HttpServerExchange exchange, final Sender sender) {
                                    //don't care
                                }

                                @Override
                                public void onException(final HttpServerExchange exchange, final Sender sender, final IOException exception) {
                                    IoUtils.safeClose(clientConnection.getConnection());
                                }
                            });
                        }
                    });

                }

                result.setResponseListener(new ResponseCallback(exchange, responseCallback, clientConnection));
                final IoExceptionHandler handler = new IoExceptionHandler(exchange, clientConnection, responseCallback);

                if(requiresContinueResponse) {
                    try {
                        if(!result.getRequestChannel().flush()) {
                            result.getRequestChannel().getWriteSetter().set(ChannelListeners.flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                                @Override
                                public void handleEvent(StreamSinkChannel channel) {
                                    ChannelListeners.initiateTransfer(Long.MAX_VALUE, exchange.getRequestChannel(), result.getRequestChannel(), ChannelListeners.closingChannelListener(), new HTTPTrailerChannelListener(exchange, result), handler, handler, exchange.getConnection().getBufferPool());

                                }
                            }, handler));
                            result.getRequestChannel().resumeWrites();
                            return;
                        }
                    } catch (IOException e) {
                        handler.handleException(result.getRequestChannel(), e);
                    }
                }

                if (emptyMessage) {
                    final StreamSinkChannel channel = result.getRequestChannel();
                    try {
                        channel.shutdownWrites();
                        if (!channel.flush()) {
                            channel.getWriteSetter().set(ChannelListeners.<StreamSinkChannel>flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                                @Override
                                public void handleEvent(StreamSinkChannel channel) {
                                    channel.suspendWrites();
                                    channel.getWriteSetter().set(null);
                                }
                            }, ChannelListeners.closingChannelExceptionHandler()));
                            channel.resumeWrites();
                        } else {
                            channel.getWriteSetter().set(null);
                            channel.shutdownWrites();
                        }
                    } catch (IOException e) {
                        handler.handleException(channel, e);
                    }
                } else {
                    ChannelListeners.initiateTransfer(Long.MAX_VALUE, exchange.getRequestChannel(), result.getRequestChannel(), ChannelListeners.closingChannelListener(), new HTTPTrailerChannelListener(exchange, result), handler, handler, exchange.getConnection().getBufferPool());
                }
            }

            @Override
            public void failed(IOException e) {
                UndertowLogger.PROXY_REQUEST_LOGGER.proxyRequestFailed(exchange.getRequestURI(), e);
                if (!exchange.isResponseStarted()) {
                    exchange.setResponseCode(503);
                    exchange.endExchange();
                } else {
                    IoUtils.safeClose(exchange.getConnection());
                }
            }
        });
    }

    private static final class ResponseCallback implements ClientCallback<ClientExchange> {

        private final HttpServerExchange exchange;
        private final ProxyConnection proxyConnection;
        private final ProxyCallback responseCallback;

        private ResponseCallback(HttpServerExchange exchange, ProxyCallback responseCallback, ProxyConnection proxyConnection) {
            this.exchange = exchange;
            this.responseCallback = responseCallback;
            this.proxyConnection = proxyConnection;
        }

        @Override
        public void completed(final ClientExchange result) {
            final ClientResponse response = result.getResponse();

            responseCallback.responseComplete(new ProxyCallback.ResponseCompletionHandle() {

                @Override
                public ClientExchange getClientExchange() {
                    return result;
                }

                @Override
                public void complete() {
                    final HeaderMap inboundResponseHeaders = response.getResponseHeaders();
                    final HeaderMap outboundResponseHeaders = exchange.getResponseHeaders();
                    exchange.setResponseCode(response.getResponseCode());
                    copyHeaders(outboundResponseHeaders, inboundResponseHeaders);

                    if (exchange.isUpgrade()) {
                        exchange.upgradeChannel(new HttpUpgradeListener() {
                            @Override
                            public void handleUpgrade(StreamConnection streamConnection, HttpServerExchange exchange) {
                                StreamConnection clientChannel = null;
                                try {
                                    clientChannel = result.getConnection().performUpgrade();

                                    ChannelListeners.initiateTransfer(Long.MAX_VALUE, clientChannel.getSourceChannel(), streamConnection.getSinkChannel(), ChannelListeners.closingChannelListener(), ChannelListeners.<StreamSinkChannel>writeShutdownChannelListener(ChannelListeners.<StreamSinkChannel>flushingChannelListener(ChannelListeners.closingChannelListener(), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler(), ChannelListeners.closingChannelExceptionHandler(), result.getConnection().getBufferPool());
                                    ChannelListeners.initiateTransfer(Long.MAX_VALUE, streamConnection.getSourceChannel(), clientChannel.getSinkChannel(), ChannelListeners.closingChannelListener(), ChannelListeners.<StreamSinkChannel>writeShutdownChannelListener(ChannelListeners.<StreamSinkChannel>flushingChannelListener(ChannelListeners.closingChannelListener(), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler()), ChannelListeners.closingChannelExceptionHandler(), ChannelListeners.closingChannelExceptionHandler(), result.getConnection().getBufferPool());

                                } catch (IOException e) {
                                    IoUtils.safeClose(streamConnection, clientChannel);
                                }
                            }
                        });
                    }
                    IoExceptionHandler handler = new IoExceptionHandler(exchange, proxyConnection, responseCallback);
                    ChannelListeners.initiateTransfer(Long.MAX_VALUE, result.getResponseChannel(), exchange.getResponseChannel(), ChannelListeners.closingChannelListener(), new HTTPTrailerChannelListener(result, exchange), handler, handler, exchange.getConnection().getBufferPool());
                }

                @Override
                public void discard() {
                    result.getResponseChannel().getReadSetter().set(ChannelListeners.drainListener(Long.MAX_VALUE, null, null));
                }
            }, proxyConnection);
        }

        @Override
        public void failed(IOException e) {
            UndertowLogger.PROXY_REQUEST_LOGGER.proxyRequestFailed(exchange.getRequestURI(), e);
            // Inform the callback of a connection failure
            responseCallback.failed(exchange, proxyConnection);
        }
    }

    private static final class HTTPTrailerChannelListener implements ChannelListener<StreamSinkChannel> {

        private final Attachable source;
        private final Attachable target;

        private HTTPTrailerChannelListener(final Attachable source, final Attachable target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public void handleEvent(final StreamSinkChannel channel) {
            HeaderMap trailers = source.getAttachment(HttpAttachments.REQUEST_TRAILERS);
            if (trailers != null) {
                target.putAttachment(HttpAttachments.RESPONSE_TRAILERS, trailers);
            }
            try {
                channel.shutdownWrites();
                if (!channel.flush()) {
                    channel.getWriteSetter().set(ChannelListeners.<StreamSinkChannel>flushingChannelListener(new ChannelListener<StreamSinkChannel>() {
                        @Override
                        public void handleEvent(StreamSinkChannel channel) {
                            channel.suspendWrites();
                            channel.getWriteSetter().set(null);
                        }
                    }, ChannelListeners.closingChannelExceptionHandler()));
                    channel.resumeWrites();
                } else {
                    channel.getWriteSetter().set(null);
                    channel.shutdownWrites();
                }
            } catch (IOException e) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(e);
                IoUtils.safeClose(channel);
            }

        }
    }

    private static final class IoExceptionHandler implements ChannelExceptionHandler<Channel> {

        private final HttpServerExchange exchange;
        private final ProxyConnection proxyConnection;
        private final ProxyCallback<ProxyConnection> proxyCallback;

        private IoExceptionHandler(HttpServerExchange exchange, ProxyConnection proxyConnection, ProxyCallback<ProxyConnection> proxyCallback) {
            this.exchange = exchange;
            this.proxyConnection = proxyConnection;
            this.proxyCallback = proxyCallback;
        }

        @Override
        public void handleException(Channel channel, IOException exception) {
            proxyCallback.failed(exchange, proxyConnection);
            UndertowLogger.REQUEST_IO_LOGGER.debug("Exception reading from target server", exception);
            IoUtils.safeClose(channel);
        }
    }

    /**
     * perform URL encoding
     * <p/>
     * TODO: this whole thing is kinda crappy.
     *
     * @return
     */
    private static String encodeUrlPart(final String part) throws UnsupportedEncodingException {
        //we need to go through and check part by part that a section does not need encoding

        int pos = 0;
        for (int i = 0; i < part.length(); ++i) {
            char c = part.charAt(i);
            if (c == '/') {
                if (pos != i) {
                    String original = part.substring(pos, i - 1);
                    String encoded = URLEncoder.encode(original, UTF_8);
                    if (!encoded.equals(original)) {
                        return realEncode(part, pos);
                    }
                }
                pos = i + 1;
            } else if (c == ' ') {
                return realEncode(part, pos);
            }
        }
        if (pos != part.length()) {
            String original = part.substring(pos);
            String encoded = URLEncoder.encode(original, UTF_8);
            if (!encoded.equals(original)) {
                return realEncode(part, pos);
            }
        }
        return part;
    }

    private static String realEncode(String part, int startPos) throws UnsupportedEncodingException {
        StringBuilder sb = new StringBuilder();
        sb.append(part.substring(0, startPos));
        int pos = startPos;
        for (int i = startPos; i < part.length(); ++i) {
            char c = part.charAt(i);
            if (c == '/') {
                if (pos != i) {
                    String original = part.substring(pos, i - 1);
                    String encoded = URLEncoder.encode(original, UTF_8);
                    sb.append(encoded);
                    sb.append('/');
                    pos = i + 1;
                }
            }
        }

        String original = part.substring(pos);
        String encoded = URLEncoder.encode(original, UTF_8);
        sb.append(encoded);
        return sb.toString();
    }

    static void copyHeaders(final HeaderMap to, final HeaderMap from) {
        long f = from.fastIterateNonEmpty();
        HeaderValues values;
        while (f != -1L) {
            values = from.fiCurrent(f);
            to.putAll(values.getHeaderName(), values);
            f = from.fiNextNonEmpty(f);
        }
    }

}
