package io.undertow.client;

/**
 * @author Emanuel Muckenhuber
 */
public interface HttpClientConnectionListener {

    /**
     * Notification that a client connection was opened.
     *
     * @param connection the opened connection
     */
    void connectionOpened(HttpClientConnection connection);

    /**
     * Notification that a connection was upgraded, therefore making it
     * unusable for further http requests.
     *
     * @param connection the upgraded connection
     */
    void connectionUpgraded(HttpClientConnection connection);

    /**
     * Notification that a connection was closed.
     *
     * @param connection the closed connection
     */
    void connectionClosed(HttpClientConnection connection);

}
