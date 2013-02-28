package io.undertow.server.handlers.proxy;

import io.undertow.client.HttpClient;
import io.undertow.client.HttpClientCallback;
import io.undertow.client.HttpClientConnection;
import io.undertow.client.HttpClientRequest;
import io.undertow.client.HttpClientResponse;
import io.undertow.test.utils.DefaultServer;
import io.undertow.util.Methods;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.IoFuture;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSourceChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.Channel;

/**
 * @author Emanuel Muckenhuber
 */
@RunWith(DefaultServer.class)
public class ProxyTest {

    private static XnioWorker worker;

    private static final InetSocketAddress destination = new InetSocketAddress(8080);

    private static final OptionMap DEFAULT_OPTIONS;
    static {
        final OptionMap.Builder builder = OptionMap.builder()
                .set(Options.WORKER_IO_THREADS, 8)
                .set(Options.TCP_NODELAY, true)
                .set(Options.KEEP_ALIVE, true)
                .set(Options.WORKER_NAME, "Client")
                ;

        DEFAULT_OPTIONS = builder.getMap();
    }


    @BeforeClass
    public static void setUp() throws Exception {
        // Create xnio worker
        final Xnio xnio = Xnio.getInstance();
        final XnioWorker xnioWorker = xnio.createWorker(null, DEFAULT_OPTIONS);
        worker = xnioWorker;

        final ProxyPass pass = SimpleProxyPass.setUp(worker, OptionMap.EMPTY, destination);
        final ProxyHttpHandler handler = new ProxyHttpHandler(pass, ProxyPathFactory.DEFAULT);
        DefaultServer.setRootHandler(handler);
    }

    @Test
    @Ignore
    public void test() throws Exception {

        final HttpClient client = HttpClient.create(worker, OptionMap.EMPTY);
        try {
            final IoFuture<HttpClientConnection> connectionFuture =  client.connect(destination, OptionMap.EMPTY);
            final HttpClientRequest request = connectionFuture.get().createRequest(Methods.GET, new URI("/"));
            request.writeRequest(new HttpClientCallback<HttpClientResponse>() {
                @Override
                public void completed(HttpClientResponse result) {
                    System.out.println(result.getContentLength());
                    try {
                        final StreamSourceChannel source = result.readReplyBody();
                        source.getReadSetter().set(ChannelListeners.drainListener(Long.MAX_VALUE,
                                new ChannelListener<StreamSourceChannel>() {
                                    @Override
                                    public void handleEvent(StreamSourceChannel channel) {
                                        channel.getReadSetter().set(null);
                                        channel.suspendReads();
                                        System.out.println("done");
                                        IoUtils.safeClose(channel);
                                    }
                                },
                                ChannelListeners.<StreamSourceChannel>closingChannelExceptionHandler()));
                        source.resumeReads();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }

                @Override
                public void failed(IOException e) {
                    e.printStackTrace();
                }
            });

            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    return;
                }
            }

        } finally {
            IoUtils.safeClose(client);
        }

    }


}
