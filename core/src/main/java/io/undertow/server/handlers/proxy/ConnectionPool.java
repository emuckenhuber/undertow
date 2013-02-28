package io.undertow.server.handlers.proxy;

import io.undertow.client.HttpClientConnection;
import io.undertow.client.HttpClientConnectionListener;

/**
 * A connection pool associated with a {@linkplain ProxyNode}.
 *
 * @author Emanuel Muckenhuber
 */
abstract class ConnectionPool implements HttpClientConnectionListener {

    private int min, max;

    private volatile ProxyConnection[] connections; // the managed connections in this pool

    /**
     * Get a connection for a proxy for a given request. This may create a new or reuse an existing
     * connection in the pool.
     *
     * @param request the proxy request
     * @return the proxy connection
     */
    abstract ProxyConnection getConnection(HttpProxyExchange request);

    @Override
    public void connectionClosed(HttpClientConnection connection) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void connectionOpened(HttpClientConnection connection) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void connectionUpgraded(HttpClientConnection connection) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

}
