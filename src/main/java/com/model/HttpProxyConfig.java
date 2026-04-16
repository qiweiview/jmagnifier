package com.model;

public class HttpProxyConfig {

    private Boolean rewriteHost = true;

    private Boolean addXForwardedHeaders = true;

    private Integer maxObjectSizeBytes = 1024 * 1024;

    public Boolean getRewriteHost() {
        return rewriteHost;
    }

    public void setRewriteHost(Boolean rewriteHost) {
        this.rewriteHost = rewriteHost;
    }

    public Boolean getAddXForwardedHeaders() {
        return addXForwardedHeaders;
    }

    public void setAddXForwardedHeaders(Boolean addXForwardedHeaders) {
        this.addXForwardedHeaders = addXForwardedHeaders;
    }

    public Integer getMaxObjectSizeBytes() {
        return maxObjectSizeBytes;
    }

    public void setMaxObjectSizeBytes(Integer maxObjectSizeBytes) {
        this.maxObjectSizeBytes = maxObjectSizeBytes;
    }

    public void applyDefaults() {
        if (rewriteHost == null) {
            rewriteHost = true;
        }
        if (addXForwardedHeaders == null) {
            addXForwardedHeaders = true;
        }
        if (maxObjectSizeBytes == null || maxObjectSizeBytes <= 0) {
            maxObjectSizeBytes = 1024 * 1024;
        }
    }
}
