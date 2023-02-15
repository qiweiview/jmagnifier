package com.core;

import com.model.HTTPDump;
import com.util.HttpResponseBuilder;
import com.util.JSONUtils;
import com.util.LiteHttpProxyPool;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.List;

/**
 * 服务器路由
 */
@Slf4j
public class HttpReadHandle extends SimpleChannelInboundHandler<FullHttpRequest> {

    public static final String NAME = "HTTP_READER";

    private GlobalConfig globalConfig;


    public HttpReadHandle(GlobalConfig globalConfig) {

        this.globalConfig = globalConfig;
    }


    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, FullHttpRequest fullHttpRequest) throws Exception {
        HTTPDump httpDump = HTTPDump.of(fullHttpRequest);
        String requestDump = JSONUtils.object2JSONString(httpDump);
        FullHttpResponse fullHttpResponse;
        if (globalConfig.isMirrorResponse()) {
            //todo 镜像返回
            fullHttpResponse = HttpResponseBuilder.jsonResponse(requestDump.getBytes());

        } else {
            //todo 转发
            LiteHttpProxy liteHttpProxy = LiteHttpProxyPool.getLiteHttpProxy();
            fullHttpResponse = liteHttpProxy.forward(globalConfig.getForwardHost(), globalConfig.getForwardPort(), fullHttpRequest.retain());
        }
        List<String> matchLocation = globalConfig.getMatchLocation();
        if (matchLocation != null && matchLocation.size() > 0) {
            //todo 存在筛选规则
            Iterator<String> iterator = matchLocation.iterator();
            while (iterator.hasNext()) {
                String next = iterator.next();
                if (fullHttpRequest.uri().startsWith(next)) {
                    //todo 命中筛选规则
                    String s = fullHttpResponse.content().copy().toString(CharsetUtil.UTF_8);
                    ByteDataProcessor.checkUnifiedOutput(() -> {
                        ByteDataProcessor.dump2Console("request", requestDump);
                        ByteDataProcessor.dump2Console("response", s);
                    }, () -> {
                        ByteDataProcessor.dump2File(s.getBytes());
                    });

                    //命中则跳出
                    break;
                }
            }

        } else {
            String s = fullHttpResponse.content().copy().toString(CharsetUtil.UTF_8);
            ByteDataProcessor.checkUnifiedOutput(() -> {
                ByteDataProcessor.dump2Console("response", s);
            }, () -> {
                ByteDataProcessor.dump2File(s.getBytes());
            });
        }

        channelHandlerContext.writeAndFlush(fullHttpResponse);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
    }
}
