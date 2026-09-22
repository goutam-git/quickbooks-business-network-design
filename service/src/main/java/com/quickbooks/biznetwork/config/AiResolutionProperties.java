package com.quickbooks.biznetwork.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-network.ai-resolution")
public class AiResolutionProperties {
    private boolean enabled = true;
    private String url = "http://localhost:8090/rank";
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 4000;
    /** Blend weight given to the AI score when combining with the deterministic
     * score (0 = ignore AI, 1 = AI score only). Deliberately NOT 1.0 -- the
     * deterministic signal always still counts, per Section 12's framing
     * that AI assists ranking rather than unilaterally deciding it. */
    private double blendWeight = 0.5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public double getBlendWeight() { return blendWeight; }
    public void setBlendWeight(double blendWeight) { this.blendWeight = blendWeight; }
}
