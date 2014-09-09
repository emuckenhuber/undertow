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

package io.undertow.server.handlers.proxy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.undertow.client.ClientResponse;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;

/**
 * @author Emanuel Muckenhuber
 */
public interface ProxyRequestErrorPolicy {

    /**
     * Get the amount of retries.
     *
     * @return the retry count
     */
    int getRetryCount();

    /**
     * Determine whether a request has to be retried on a different node.
     *
     * @param response    the client response
     * @return
     */
    boolean retryNextNode(final ClientResponse response);

    /**
     * Determine whether a request can be retried.
     *
     * @param exchange    the http server exchange
     * @return
     */
    boolean canRetryRequest(final HttpServerExchange exchange);

    HttpString[] SAFE_METHODS = new HttpString[] { Methods.HEAD, Methods.GET, Methods.OPTIONS, Methods.TRACE };
    HttpString[] IDEMPOTENT_METHODS = new HttpString[] { Methods.DELETE, Methods.HEAD, Methods.GET, Methods.OPTIONS, Methods.PUT, Methods.TRACE };

    ProxyRequestErrorPolicy NO_RETRY = new ProxyRequestErrorPolicy() {
        @Override
        public int getRetryCount() {
            return 0;
        }

        @Override
        public boolean retryNextNode(ClientResponse response) {
            return false;
        }

        @Override
        public boolean canRetryRequest(HttpServerExchange exchange) {
            return false;
        }
    };

    public abstract class AbstractRequestPolicy implements ProxyRequestErrorPolicy {

        private final Set<HttpString> allowedMethods;
        private final boolean allowQueryString;

        protected AbstractRequestPolicy(HttpString... allowedMethods) {
            this.allowedMethods = new HashSet<>();
            Collections.addAll(this.allowedMethods, allowedMethods);
            allowQueryString = true;
        }

        protected AbstractRequestPolicy(Set<HttpString> allowedMethods, final boolean allowQueryString) {
            this.allowedMethods = allowedMethods;
            this.allowQueryString = allowQueryString;
        }

        @Override
        public boolean canRetryRequest(HttpServerExchange exchange) {
            if (!allowQueryString && exchange.getQueryString() != null) {
                return false;
            }
            return allowedMethods.contains(exchange.getRequestMethod());
        }
    }

}
