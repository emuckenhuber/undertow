package io.undertow.server.handlers.proxy;

import java.io.IOException;

/**
 * @author Emanuel Muckenhuber
 */
public interface ProxyPass {

    /**
     * Handle a request
     *
     * @param request the proxy request
     * @return {@code true} if the request can be proxied
     * @throws IOException
     */
    boolean handle(HttpProxyExchange request) throws IOException;

}
