package io.undertow.server.handlers.proxy;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * @author Emanuel Muckenhuber
 */
public interface ProxyPathFactory {

    URI create(ProxyHttpRequest request) throws URISyntaxException;

    ProxyPathFactory DEFAULT = new ProxyPathFactory() {
        @Override
        public URI create(final ProxyHttpRequest request) throws URISyntaxException {
            final String path = request.getRelativePath();
            final String query = request.getQueryString();
            return new URI(null, null, null, -1, path, query, null);
        }
    };

}
