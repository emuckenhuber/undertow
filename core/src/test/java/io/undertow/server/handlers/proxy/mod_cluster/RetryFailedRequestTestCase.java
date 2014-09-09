/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server.handlers.proxy.mod_cluster;

import java.io.IOException;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Emanuel Muckenhuber
 */
public class RetryFailedRequestTestCase extends AbstractModClusterTestBase {

    static NodeTestConfig server1;
    static NodeTestConfig server2;

    static volatile HttpHandler handler1 = ResponseCodeHandler.HANDLE_200;
    static volatile HttpHandler handler2 = ResponseCodeHandler.HANDLE_200;

    static {
        server1 = NodeTestConfig.builder()
                .setJvmRoute("s1")
                .setType(getType())
                .setHostname("localhost")
                .setPort(port + 1)
                .setStickySessionForce(false)
                .setTestHandlers(new NodeTestHandlers() {
                    @Override
                    public void setup(PathHandler handler, NodeTestConfig config) {
                        handler.addPrefixPath("fail-s1", ResponseCodeHandler.HANDLE_500);
                        handler.addPrefixPath("fail-all", ResponseCodeHandler.HANDLE_500);
                        handler.addPrefixPath("test-handler", new HttpHandler() {
                            @Override
                            public void handleRequest(HttpServerExchange exchange) throws Exception {
                                handler1.handleRequest(exchange);
                            }
                        });
                    }
                });

        server2 = NodeTestConfig.builder()
                .setJvmRoute("s2")
                .setType(getType())
                .setHostname("localhost")
                .setPort(port + 2)
                .setStickySessionForce(false)
                .setTestHandlers(new NodeTestHandlers() {
                    @Override
                    public void setup(PathHandler handler, NodeTestConfig config) {
                        handler.addPrefixPath("fail-s1", ResponseCodeHandler.HANDLE_200);
                        handler.addPrefixPath("fail-all", ResponseCodeHandler.HANDLE_500);
                        handler.addPrefixPath("test-handler", new HttpHandler() {
                            @Override
                            public void handleRequest(HttpServerExchange exchange) throws Exception {
                                handler2.handleRequest(exchange);
                            }
                        });
                    }
                });
    }

    @BeforeClass
    public static void setup() {
        startServers(server1, server2);
    }

    @AfterClass
    public static void tearDown() {
        stopServers();
    }

    @After
    public void cleanupHandlers() {
        handler1 = ResponseCodeHandler.HANDLE_200;
        handler2 = ResponseCodeHandler.HANDLE_200;
    }

    @Test
    public void testFailS1() throws IOException {
        registerNodes(true, server1, server2);

        modClusterClient.enableApp("s1", "/fail-s1", "localhost", "localhost:7777");
        modClusterClient.enableApp("s2", "/fail-s1", "localhost", "localhost:7777");

        checkGet("/fail-s1", 200, "s1");
    }

    @Test
    public void testFailAll() throws IOException {
        registerNodes(true, server1, server2);

        modClusterClient.enableApp("s1", "/fail-all", "localhost", "localhost:7777");
        modClusterClient.enableApp("s2", "/fail-all", "localhost", "localhost:7777");

        checkGet("/fail-all", 500, "s1");
    }

    @Test
    public void testConnectionFailureS1() throws IOException {
        registerNodes(true, server1, server2);

        handler1 = new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                exchange.getConnection().close();
            }
        };

        modClusterClient.enableApp("s1", "/test-handler", "localhost", "localhost:7777");
        modClusterClient.enableApp("s2", "/test-handler", "localhost", "localhost:7777");

        checkGet("/test-handler", 200, "s1");
    }

    @Test
    public void testConnectionFailureAll() throws IOException {
        registerNodes(true, server1, server2);

        final HttpHandler handler = new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                exchange.getConnection().close();
            }
        };
        handler1 = handler;
        handler2 = handler;

        modClusterClient.enableApp("s1", "/test-handler", "localhost", "localhost:7777");
        modClusterClient.enableApp("s2", "/test-handler", "localhost", "localhost:7777");

        checkGet("/test-handler", 500, "s1");
    }

}
