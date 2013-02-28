package io.undertow.server.handlers.proxy;

import io.undertow.UndertowLogger;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.HttpHandlers;
import io.undertow.server.handlers.ResponseCodeHandler;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.Pool;

import java.nio.ByteBuffer;

/**
 * @author Emanuel Muckenhuber
 */
public class ProxyHttpHandler implements HttpHandler {

    private final HttpHandler next;
    private final ProxyPass proxyPass;

    private final Pool<ByteBuffer> bufferPool = new ByteBufferSlicePool(BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR, 1024 * 4, 4096 * 20);

    private final ProxyRequestHandler requestHandler;
    private final ProxyResponseHandler responseHandler = new ProxyResponseHandler();

    public ProxyHttpHandler(final ProxyPass proxyPass, final ProxyPathFactory pathFactory) {
        this(proxyPass, pathFactory, ResponseCodeHandler.HANDLE_404);
    }

    public ProxyHttpHandler(final ProxyPass proxyPass, final ProxyPathFactory pathFactory, final HttpHandler next) {
        this.next = next;
        this.proxyPass = proxyPass;
        this.requestHandler = new ProxyRequestHandler(pathFactory);
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) {
        try {
            final HttpProxyExchange proxyExchange = new HttpProxyExchange(exchange, requestHandler, responseHandler, bufferPool);
            if (!proxyPass.handle(proxyExchange)) {
                HttpHandlers.executeHandler(next, exchange);
            }
        } catch (Exception e) {
            UndertowLogger.REQUEST_LOGGER.exceptionProcessingRequest(e);
            exchange.setResponseCode(500);
            exchange.endExchange();
        }
    }

}
