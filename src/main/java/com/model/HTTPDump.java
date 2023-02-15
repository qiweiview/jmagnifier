package com.model;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.CharsetUtil;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class HTTPDump {
    private String method;

    private String location;

    private Map<String, String> headerMap;

    private String body;

    public static HTTPDump of(FullHttpRequest fullHttpRequest) {
        HTTPDump httpDump = new HTTPDump();
        httpDump.setMethod(fullHttpRequest.method().name());
        httpDump.setLocation(fullHttpRequest.uri());


        HttpHeaders headers = fullHttpRequest.headers();
        Map<String, String> headerMap = new HashMap<>();
        headers.forEach(x -> {
            headerMap.put(x.getKey(), x.getValue());
        });
        httpDump.setHeaderMap(headerMap);
        ByteBuf jsonBuf = fullHttpRequest.content();
        String jsonStr = jsonBuf.toString(CharsetUtil.UTF_8);
        httpDump.setBody(jsonStr);
        return httpDump;
    }
}
