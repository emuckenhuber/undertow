package io.undertow.server.handlers.proxy;

import io.undertow.UndertowLogger;
import io.undertow.client.HttpClientCallback;
import io.undertow.client.HttpClientConnection;
import io.undertow.client.HttpClientRequest;
import io.undertow.client.HttpClientResponse;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.Pool;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Proxy request handler.
 *
 * @author Emanuel Muckenhuber
 */
class ProxyRequestHandler {

    private static final HttpString X_FORWARDED_FOR = new HttpString("X-Forwarded-For");

    // A list of headers which should NOT be forwarded unmodified
    private static final Set<HttpString> ignoredHeaders = new HashSet<>();

    static {
        ignoredHeaders.add(Headers.CONNECTION);
        ignoredHeaders.add(Headers.CONTENT_LENGTH);
        ignoredHeaders.add(Headers.MAX_FORWARDS); // Only useful for TRACE
    }

    private final ProxyPathFactory pathFactory;

    ProxyRequestHandler(ProxyPathFactory pathFactory) {
        this.pathFactory = pathFactory;
    }

    /**
     * Prepare the proxy request.
     *
     * @param request  the http request to proxy
     * @param exchange the proxy exchange
     * @return the content-length
     * @throws IOException
     */
    protected void handleRequest(final ProxyHttpRequest request, final ProxyConnection proxyConnection, final HttpProxyExchange exchange) throws Exception {

        final HttpString method = request.getRequestMethod();
        final HeaderMap headers = request.getRequestHeaders();

        long contentLength = determineContentLength(method, headers);
        if (contentLength == -1 && exchange.getExchange().isPersistent()) {
            // In case it's a persistent connection, not declaring any content-length or transfer encoding
            if (!headers.contains(Headers.TRANSFER_ENCODING)) {
                contentLength = 0;
            }
        }

        final int maxForwards = determineMaxForwards(method, headers);
        if (maxForwards == 0) {
            // TODO endExchange()
        }

        // Get request path
        final URI requestURI = pathFactory.create(request);
        final HttpClientConnection connection = proxyConnection.getConnection();
        final HttpClientRequest proxyRequest = connection.createRequest(method, requestURI);
        // In case the connection is closed fail over
        if (proxyRequest == null) {
            exchange.handleConnectionClosed(proxyConnection);
            return;
        }

        // Forward headers
        final HeaderMap proxyHeaders = proxyRequest.getRequestHeaders();
        final Iterator<HttpString> i = headers.iterator();
        while (i.hasNext()) {
            final HttpString header = i.next();
            if (!ignoredHeaders.contains(header)) {
                proxyHeaders.putAll(header, headers.get(header));
            }
        }

        // Always try to reuse the connection
        proxyHeaders.put(Headers.CONNECTION, Headers.KEEP_ALIVE.toString());

        // Max-Forwards
        if (maxForwards > 0) {
            proxyHeaders.put(Headers.MAX_FORWARDS, maxForwards - 1);
        }

        // X-Forwarded-For
        List<String> xForwardedFor = headers.get(X_FORWARDED_FOR);
        if (xForwardedFor == null) {
            xForwardedFor = new ArrayList<>();
        }
        final String forwardedFor = determineClientAddress(exchange);
        if (forwardedFor != null) {
            xForwardedFor.add(forwardedFor);
            proxyHeaders.putAll(X_FORWARDED_FOR, xForwardedFor);
        }

        UndertowLogger.REQUEST_LOGGER.infof("forwarding request %s with headers %s", proxyRequest, proxyHeaders);

        //
        // Handle message body
        //
        final boolean retryRequestOnFailure = contentLength == 0L;
        final HttpClientCallback<HttpClientResponse> callback = new HttpClientCallback<HttpClientResponse>() {
            @Override
            public void completed(final HttpClientResponse result) {
                exchange.handleResponse(result);
            }

            @Override
            public void failed(final IOException e) {
                if (retryRequestOnFailure) {
                    exchange.handleConnectionFailure(proxyConnection, e);
                } else {
                    exchange.handleFailed(e);
                }
            }
        };
        if (contentLength == 0L) {
            proxyRequest.writeRequest(callback);
            return;
        } else {
            // Transfer message body
            final Pool<ByteBuffer> bufferPool = exchange.getBufferPool();
            final StreamSinkChannel sink = proxyRequest.writeRequestBody(contentLength, callback);
            final StreamSourceChannel source = exchange.getRequestChannel();
            TempChannelListeners.initiateTransfer(Long.MAX_VALUE, source, sink, CLOSE_LISTENER, FLUSHING_CLOSE_LISTENER, CLOSING_EXCEPTION_LISTENER, CLOSING_EXCEPTION_LISTENER, bufferPool);
        }
    }

    String determineClientAddress(HttpProxyExchange exchange) {
        final InetSocketAddress address = exchange.getClientAddress();
        try {
            return address.getHostString();
        } catch (Exception e) {
            return null;
        }
    }

    long determineContentLength(final HttpString method, final HeaderMap headers) throws NumberFormatException {
        if (headers.contains(Headers.CONTENT_LENGTH)) {
            return Long.parseLong(headers.getFirst(Headers.CONTENT_LENGTH));
        } else {
            return -1L;
        }
    }

    int determineMaxForwards(final HttpString method, final HeaderMap headers) throws NumberFormatException {
        if (Methods.TRACE.equals(method)) {
            final String count = headers.getFirst(Headers.MAX_FORWARDS);
            if (count != null) {
                return Integer.parseInt(count);
            }
        }
        return -1;
    }

    private static final ChannelListener<StreamSinkChannel> FLUSHING_CLOSE_LISTENER = ProxyUtils.flushingChannelCloseListener();
    private static final ChannelListener<Channel> CLOSE_LISTENER = ChannelListeners.closingChannelListener();
    private static final ChannelExceptionHandler<Channel> CLOSING_EXCEPTION_LISTENER = new ChannelExceptionHandler<Channel>() {
        @Override
        public void handleException(Channel channel, IOException exception) {
            exception.printStackTrace();
            CLOSE_LISTENER.handleEvent(channel);
        }
    };

}
