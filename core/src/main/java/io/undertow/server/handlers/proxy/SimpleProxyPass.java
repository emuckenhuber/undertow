package io.undertow.server.handlers.proxy;

import io.undertow.client.HttpClient;
import io.undertow.client.HttpClientConnection;
import io.undertow.util.AttachmentKey;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.XnioWorker;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * @author Emanuel Muckenhuber
 */
public class SimpleProxyPass extends ConnectionPool implements ProxyPass {

    static final AttachmentKey<ProxyConnection> PROXY_CONNECTION_KEY = AttachmentKey.create(ProxyConnection.class);

    private final HttpClient client;
    private final InetSocketAddress destination;

    public SimpleProxyPass(HttpClient client, InetSocketAddress target) {
        this.client = client;
        this.destination = target;
    }

    @Override
    public boolean handle(HttpProxyExchange request) throws IOException {

        final ProxyConnection connection = getConnection(request);
        if (connection != null) {
            connection.executeRequest(request);
            return true;
        }
        return false;
    }

    @Override
    ProxyConnection getConnection(final HttpProxyExchange request) {
        final ProxyConnection cached = request.getExchange().getConnection().getAttachment(PROXY_CONNECTION_KEY);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        final IoFuture<HttpClientConnection> connectionIoFuture = client.connect(destination, OptionMap.EMPTY);
        final ProxyConnection proxyConnection = new ProxyConnection(this, connectionIoFuture);
        request.getExchange().getConnection().putAttachment(PROXY_CONNECTION_KEY, proxyConnection);
        return proxyConnection;
    }

    static SimpleProxyPass setUp(final XnioWorker worker, OptionMap options, final InetSocketAddress destination) {
        final HttpClient client = HttpClient.create(worker, options);
        return new SimpleProxyPass(client, destination);
    }

}
