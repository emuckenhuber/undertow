package io.undertow.server.handlers.proxy;

/**
 * A proxy node.
 *
 * @author Emanuel Muckenhuber
 */
interface ProxyNode {

    enum State {

        DISCONNECTED,
        CONNECTED,
        PAUSED,
        BACKUP,;

    }

    /**
     * Get the current state of this node.
     *
     * @return the node state
     */
    State getNodeState();

    /**
     * Get the connection pool.
     *
     * @return the connection
     */
    ConnectionPool getConnectionPool();

}
