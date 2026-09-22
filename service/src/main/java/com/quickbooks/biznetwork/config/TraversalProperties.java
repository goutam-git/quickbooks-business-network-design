package com.quickbooks.biznetwork.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-network.traversal")
public class TraversalProperties {
    private int maxDepth = 3;
    private int maxExploredNodes = 5000;
    private int maxExploredEdges = 20000;
    private int maxReturnedNodes = 200;
    private int maxReturnedEdges = 500;
    private int defaultPageSize = 50;

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
    public int getMaxExploredNodes() { return maxExploredNodes; }
    public void setMaxExploredNodes(int maxExploredNodes) { this.maxExploredNodes = maxExploredNodes; }
    public int getMaxExploredEdges() { return maxExploredEdges; }
    public void setMaxExploredEdges(int maxExploredEdges) { this.maxExploredEdges = maxExploredEdges; }
    public int getMaxReturnedNodes() { return maxReturnedNodes; }
    public void setMaxReturnedNodes(int maxReturnedNodes) { this.maxReturnedNodes = maxReturnedNodes; }
    public int getMaxReturnedEdges() { return maxReturnedEdges; }
    public void setMaxReturnedEdges(int maxReturnedEdges) { this.maxReturnedEdges = maxReturnedEdges; }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }
}
