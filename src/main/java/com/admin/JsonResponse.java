package com.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class JsonResponse {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static FullHttpResponse success(Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return json(HttpResponseStatus.OK, body);
    }

    public static FullHttpResponse success() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        return json(HttpResponseStatus.OK, body);
    }

    public static FullHttpResponse error(HttpResponseStatus status, String code, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", error);
        return json(status, body);
    }

    public static FullHttpResponse text(HttpResponseStatus status, String text, String contentType) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType + "; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return response;
    }

    private static FullHttpResponse json(HttpResponseStatus status, Object body) {
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(body);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            return response;
        } catch (JsonProcessingException e) {
            return text(HttpResponseStatus.INTERNAL_SERVER_ERROR, "{\"success\":false}", "application/json");
        }
    }
}
