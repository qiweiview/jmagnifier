package com.admin;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdminRouter {

    private final List<Route> routes = new ArrayList<>();

    public AdminRouter add(HttpMethod method, String pattern, AdminRouteHandler handler) {
        routes.add(new Route(method, pattern, handler));
        return this;
    }

    public FullHttpResponse route(FullHttpRequest request) {
        String path = new QueryStringDecoder(request.uri()).path();
        for (Route route : routes) {
            Map<String, String> params = route.match(request.method(), path);
            if (params != null) {
                return route.handler.handle(request, params);
            }
        }
        return null;
    }

    private static class Route {

        private final HttpMethod method;

        private final AdminRouteHandler handler;

        private final Pattern regex;

        private final List<String> paramNames;

        private Route(HttpMethod method, String pattern, AdminRouteHandler handler) {
            this.method = method;
            this.handler = handler;
            this.paramNames = new ArrayList<>();
            this.regex = Pattern.compile(toRegex(pattern, paramNames));
        }

        private Map<String, String> match(HttpMethod requestMethod, String path) {
            if (!method.equals(requestMethod)) {
                return null;
            }
            Matcher matcher = regex.matcher(path);
            if (!matcher.matches()) {
                return null;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), matcher.group(i + 1));
            }
            return params;
        }

        private static String toRegex(String pattern, List<String> paramNames) {
            StringBuilder regex = new StringBuilder("^");
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char value = pattern.charAt(i);
                if (value == '{') {
                    if (literal.length() > 0) {
                        regex.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    int end = pattern.indexOf('}', i);
                    if (end < 0) {
                        throw new IllegalArgumentException("invalid route pattern: " + pattern);
                    }
                    paramNames.add(pattern.substring(i + 1, end));
                    regex.append("([^/]+)");
                    i = end;
                } else {
                    literal.append(value);
                }
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
            }
            regex.append("$");
            return regex.toString();
        }
    }
}
