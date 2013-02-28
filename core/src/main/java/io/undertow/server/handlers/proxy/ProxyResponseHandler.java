package io.undertow.server.handlers.proxy;

import io.undertow.UndertowLogger;
import io.undertow.client.HttpClientResponse;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.xnio.ChannelExceptionHandler;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.Pool;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Handler for the proxy response.
 *
 * @author Emanuel Muckenhuber
 */
class ProxyResponseHandler {

    // A list of headers which should NOT be forwarded
    private static final Set<HttpString> ignoredHeaders = new HashSet<>();

    static {
        ignoredHeaders.add(Headers.CONNECTION);
        ignoredHeaders.add(Headers.CONTENT_LENGTH);
    }

    void handleResponse(final HttpClientResponse response, final HttpProxyExchange exchange) throws IOException {
        UndertowLogger.REQUEST_LOGGER.infof("received response %s", response);

        final HeaderMap proxyHeaders = response.getResponseHeaders();
        final Iterator<HttpString> i = proxyHeaders.iterator();
        while (i.hasNext()) {
            final HttpString header = i.next();
            if (!ignoredHeaders.contains(header)) {
                exchange.getResponseHeaders().putAll(header, proxyHeaders.get(header));
            }
        }

        // Update the response code
        exchange.getExchange().setResponseCode(response.getResponseCode());

        // Update the content length
        final long contentLength = response.getContentLength();
        if (contentLength == 0L) {
            // end exchange for empty messages directly
            exchange.endExchange();
            return;
        } else {
            if (contentLength >= 0L) {
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, contentLength);
            }

            final ChannelListener<StreamSinkChannel> requestFinished = new ChannelListener<StreamSinkChannel>() {
                @Override
                public void handleEvent(StreamSinkChannel channel) {
                    channel.getWriteSetter().set(null);
                    channel.suspendWrites();
                    exchange.endExchange();
                }
            };

            // Transfer message body
            final Pool<ByteBuffer> bufferPool = exchange.getBufferPool();
            final StreamSourceChannel source = response.readReplyBody();
            final StreamSinkChannel sink = exchange.getResponseChannel();
            TempChannelListeners.initiateTransfer(Long.MAX_VALUE, source, sink, CLOSE_LISTENER, requestFinished, CLOSING_EXCEPTION_LISTENER, CLOSING_EXCEPTION_LISTENER, bufferPool);
        }
    }

    private static final ChannelListener<Channel> CLOSE_LISTENER = ChannelListeners.closingChannelListener();
    private static final ChannelExceptionHandler<Channel> CLOSING_EXCEPTION_LISTENER = new ChannelExceptionHandler<Channel>() {
        @Override
        public void handleException(Channel channel, IOException exception) {
            exception.printStackTrace();
            CLOSE_LISTENER.handleEvent(channel);
        }
    };

}
