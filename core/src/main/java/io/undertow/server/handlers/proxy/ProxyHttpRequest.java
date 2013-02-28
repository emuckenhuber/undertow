package io.undertow.server.handlers.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

import java.net.InetSocketAddress;
import java.util.Deque;
import java.util.Map;

/**
 * @author Emanuel Muckenhuber
 */
class ProxyHttpRequest {

    private final HttpServerExchange exchange;

    ProxyHttpRequest(HttpServerExchange exchange) {
        this.exchange = exchange;
    }

    public HttpString getProtocol() {
        return exchange.getProtocol();
    }

    public boolean isHttp09() {
        return exchange.isHttp09();
    }

    public boolean isHttp10() {
        return exchange.isHttp10();
    }

    public boolean isHttp11() {
        return exchange.isHttp11();
    }

    public HttpString getRequestMethod() {
        return exchange.getRequestMethod();
    }

    public String getRequestScheme() {
        return exchange.getRequestScheme();
    }

    public String getRequestURI() {
        return exchange.getRequestURI();
    }

    public String getRequestPath() {
        return exchange.getRequestPath();
    }

    public String getRelativePath() {
        return exchange.getRelativePath();
    }

    public String getResolvedPath() {
        return exchange.getResolvedPath();
    }

    public String getCanonicalPath() {
        return exchange.getCanonicalPath();
    }

    public String getQueryString() {
        return exchange.getQueryString();
    }

    public String getRequestURL() {
        return exchange.getRequestURL();
    }

    public boolean isPersistent() {
        return exchange.isPersistent();
    }

    public boolean isUpgrade() {
        return exchange.isUpgrade();
    }

    public InetSocketAddress getSourceAddress() {
        return exchange.getSourceAddress();
    }

    public InetSocketAddress getDestinationAddress() {
        return exchange.getDestinationAddress();
    }

    public HeaderMap getRequestHeaders() {
        return exchange.getRequestHeaders();
    }

    public Map<String, Deque<String>> getQueryParameters() {
        return exchange.getQueryParameters();
    }

}
