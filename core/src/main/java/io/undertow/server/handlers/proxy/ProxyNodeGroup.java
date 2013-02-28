package io.undertow.server.handlers.proxy;

/**
 * A potentially load balancing group of proxy nodes.
 *
 * @author Emanuel Muckenhuber
 */
public abstract class ProxyNodeGroup {

    private volatile ProxyNode[] nodes; // the managed nodes in this group

    /**
     * Select a node out of this group.
     *
     * @param exchange the request
     * @return the selected node, {@code null} if no node is available
     */
    public abstract ProxyNode selectNode(HttpProxyExchange exchange);

    protected abstract void addNode(ProxyNode node);
    protected abstract void removeNode(ProxyNode node);

}
