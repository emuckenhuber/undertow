package io.undertow.server.handlers.proxy;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.client.HttpClientResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import org.xnio.Pool;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.xnio.Bits.anyAreSet;

/**
 * @author Emanuel Muckenhuber
 */
class HttpProxyExchange {

    private final HttpServerExchange exchange;
    private final ProxyRequestHandler requestHandler;
    private final ProxyResponseHandler responseHandler;
    private final Pool<ByteBuffer> bufferPool;

    private volatile StreamSinkChannel responseChannel;

    private volatile int state = 0;
    private static final AtomicIntegerFieldUpdater<HttpProxyExchange> stateUpdater = AtomicIntegerFieldUpdater.newUpdater(HttpProxyExchange.class, "state");

    //
    private static final int MAX_RETRIES = 5;

    //
    private static final int RESPONSE = 1 << 29;
    private static final int COMPLETED = 1 << 31;

    HttpProxyExchange(HttpServerExchange exchange, ProxyRequestHandler requestHandler, ProxyResponseHandler responseHandler, Pool<ByteBuffer> bufferPool) {
        this.exchange = exchange;
        this.requestHandler = requestHandler;
        this.responseHandler = responseHandler;
        this.bufferPool = bufferPool;
    }

    protected StreamSourceChannel getRequestChannel() {
        return exchange.getRequestChannel();
    }

    public Pool<ByteBuffer> getBufferPool() {
        return bufferPool;
    }

    void prepareRequest(final ProxyConnection connection) {
        if (anyAreSet(state, RESPONSE | COMPLETED)) {
            throw new IllegalStateException();
        }
        try {
            final ProxyHttpRequest request = new ProxyHttpRequest(exchange);
            requestHandler.handleRequest(request, connection, this);
        } catch (Exception e) {
            handleFailed(e);
        }
    }

    void handleResponse(final HttpClientResponse response) {
        int oldVal, newVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, RESPONSE)) {
                return;
            }
            newVal = oldVal | RESPONSE;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));
        try {
            responseHandler.handleResponse(response, this);
        } catch (Exception e) {
            handleFailed(e);
        }
    }

    protected void handleConnectionFailure(ProxyConnection connection, Exception e) {
        UndertowLogger.ROOT_LOGGER.warnf(e, "connection failed. retrying");
        if (anyAreSet(state, COMPLETED)) {
            handleFailed(e);
            return;
        }
        handleConnectionClosed(connection);
    }

    protected void handleConnectionClosed(ProxyConnection connection) {
        if (anyAreSet(state, COMPLETED)) {
            handleFailed(UndertowMessages.MESSAGES.streamIsClosed());
            return;
        }
        // TODO proper connection handling
        exchange.getConnection().removeAttachment(SimpleProxyPass.PROXY_CONNECTION_KEY);
        //
        connection.getPool().getConnection(this).executeRequest(this);
    }

    protected void handleFailed(Exception e) {
        e.printStackTrace();
        int oldVal, newVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, COMPLETED)) {
                return;
            }
            newVal = oldVal | COMPLETED;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));

        // TODO proper connection handling
        exchange.getConnection().removeAttachment(SimpleProxyPass.PROXY_CONNECTION_KEY);

        UndertowLogger.REQUEST_LOGGER.exceptionProcessingRequest(e);
        exchange.setResponseCode(500);
        exchange.setPersistent(false);
        exchange.endExchange();
    }

    public InetSocketAddress getClientAddress() {
        return exchange.getConnection().getPeerAddress(InetSocketAddress.class);
    }

    public StreamSinkChannel getResponseChannel() {
        if (responseChannel == null) {
            responseChannel = exchange.getResponseChannel();
        }
        return responseChannel;
    }

    public HeaderMap getResponseHeaders() {
        return exchange.getResponseHeaders();
    }

    protected void endExchange() {
        int oldVal, newVal;
        do {
            oldVal = state;
            if (anyAreSet(oldVal, COMPLETED)) {
                return;
            }
            newVal = oldVal | COMPLETED;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));
        exchange.endExchange();
    }

    HttpServerExchange getExchange() {
        return exchange;
    }
}
