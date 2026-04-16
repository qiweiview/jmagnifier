package com.protocol.http;

class HttpRequestState {

    private final String method;

    private final String uri;

    private final boolean keepAlive;

    HttpRequestState(String method, String uri, boolean keepAlive) {
        this.method = method;
        this.uri = uri;
        this.keepAlive = keepAlive;
    }

    String getMethod() {
        return method;
    }

    String getUri() {
        return uri;
    }

    boolean isKeepAlive() {
        return keepAlive;
    }
}
