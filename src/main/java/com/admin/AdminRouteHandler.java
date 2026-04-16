package com.admin;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.Map;

public interface AdminRouteHandler {

    FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams);
}
