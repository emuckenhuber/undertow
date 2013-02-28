package io.undertow.server.handlers.proxy;

import io.undertow.client.HttpClientConnection;
import org.xnio.IoFuture;

import java.io.IOException;

/**
 * @author Emanuel Muckenhuber
 */
class ProxyConnection {

    private final ConnectionPool pool;
    private final IoFuture<HttpClientConnection> connectionIoFuture;

    ProxyConnection(ConnectionPool pool, IoFuture<HttpClientConnection> connectionIoFuture) {
        this.pool = pool;
        this.connectionIoFuture = connectionIoFuture;
    }

    ConnectionPool getPool() {
        return pool;
    }

    protected HttpClientConnection getConnection() {
        if (connectionIoFuture.getStatus() == IoFuture.Status.DONE) {
            try {
                return connectionIoFuture.get();
            } catch (IOException e) {
                throw new IllegalStateException(); // not possible
            }
        }
        throw new IllegalStateException(); // wrong usage
    }

    protected void executeRequest(final HttpProxyExchange request) {
        if (connectionIoFuture.getStatus() == IoFuture.Status.DONE) {
            final HttpClientConnection connection = getConnection();
            executeRequest(connection, request);
        } else {
            connectionIoFuture.addNotifier(new IoFuture.HandlingNotifier<HttpClientConnection, Void>() {
                @Override
                public void handleFailed(IOException exception, Void attachment) {
                    request.handleConnectionFailure(ProxyConnection.this, exception);
                }

                @Override
                public void handleDone(HttpClientConnection connection, Void attachment) {
                    executeRequest(connection, request);
                }
            }, null);
        }
    }

    protected void executeRequest(final HttpClientConnection connection, final HttpProxyExchange request) {
        request.prepareRequest(this);
    }

    protected boolean isClosed() {
        if (connectionIoFuture.getStatus() == IoFuture.Status.DONE) {
            return getConnection().isClosed();
        }
        return false;
    }

}
