package com.admin;

import io.netty.handler.codec.http.HttpResponseStatus;

public class AdminApiException extends RuntimeException {

    private final HttpResponseStatus status;

    private final String code;

    public AdminApiException(HttpResponseStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
