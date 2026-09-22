package com.quickbooks.biznetwork.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-network.path-search")
public class PathSearchProperties {
    private int maxDepth = 4;
    private int maxExploredNodes = 5000;

    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
    public int getMaxExploredNodes() { return maxExploredNodes; }
    public void setMaxExploredNodes(int maxExploredNodes) { this.maxExploredNodes = maxExploredNodes; }
}
