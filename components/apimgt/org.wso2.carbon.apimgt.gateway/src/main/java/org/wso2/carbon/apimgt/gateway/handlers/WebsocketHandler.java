/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.apimgt.gateway.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.gateway.InboundMessageContextDataHolder;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLResponseProcessor;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;

public class WebsocketHandler extends CombinedChannelDuplexHandler<WebsocketInboundHandler, WebsocketOutboundHandler> {

    private static final Log log = LogFactory.getLog(WebsocketInboundHandler.class);
    private static GraphQLResponseProcessor graphQLResponseProcessor = new GraphQLResponseProcessor();

    public WebsocketHandler() {
        super(new WebsocketInboundHandler(), new WebsocketOutboundHandler());
    }

    public WebsocketHandler(WebsocketInboundHandler websocketInboundHandler,
                            WebsocketOutboundHandler websocketOutboundHandler) {
        super(websocketInboundHandler, websocketOutboundHandler);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        String channelId = ctx.channel().id().asLongText();
        InboundMessageContext inboundMessageContext;
        if (InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().containsKey(channelId)) {
            inboundMessageContext = InboundMessageContextDataHolder.getInstance()
                    .getInboundMessageContextForConnectionId(channelId);
        } else {
            inboundMessageContext = new InboundMessageContext();
            InboundMessageContextDataHolder.getInstance()
                    .addInboundMessageContextForConnection(channelId, inboundMessageContext);
        }

        if ((msg instanceof CloseWebSocketFrame) || (msg instanceof PongWebSocketFrame)) {
            //remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().remove(channelId);
            //if the inbound frame is a closed frame, throttling, analytics will not be published.
            outboundHandler().write(ctx, msg, promise);

        } else if (msg instanceof WebSocketFrame) {

            if (APIConstants.APITransportType.GRAPHQL.toString()
                    .equals(inboundMessageContext.getElectedAPI().getApiType()) && msg instanceof TextWebSocketFrame) {
                // Authenticate and handle GraphQL subscription responses
                InboundProcessorResponseDTO responseDTO = graphQLResponseProcessor.handleResponse((WebSocketFrame) msg,
                        ctx, inboundMessageContext, inboundHandler().getUsageDataPublisher());
                if (responseDTO.isError()) {
                    if (responseDTO.isCloseConnection()) {
                        // remove inbound message context from data holder
                        InboundMessageContextDataHolder.getInstance().removeInboundMessageContextForConnection(channelId);
                        if (log.isDebugEnabled()) {
                            log.debug("Error while handling Outbound Websocket frame. Closing connection for "
                                    + ctx.channel().toString());
                        }
                        outboundHandler().write(ctx, new CloseWebSocketFrame(responseDTO.getErrorCode(),
                                responseDTO.getErrorMessage() + StringUtils.SPACE + "Connection closed" + "!"), promise);
                        outboundHandler().flush(ctx);
                        outboundHandler().close(ctx, promise);
                    } else {
                        String errorMessage = responseDTO.getErrorResponseString();
                        outboundHandler().write(ctx, new TextWebSocketFrame(errorMessage), promise);
                        if (responseDTO.getErrorCode() == GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR) {
                            if (log.isDebugEnabled()) {
                                log.debug("Outbound Websocket frame is throttled. " + ctx.channel().toString());
                            }
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Sending Outbound Websocket frame." + ctx.channel().toString());
                    }
                    outboundHandler().write(ctx, msg, promise);
                }
            } else {
                // If not a GraphQL API (Only a WebSocket API)
                if (isAllowed(ctx, (WebSocketFrame) msg, inboundMessageContext,
                        inboundHandler().getUsageDataPublisher())) {
                    handleWSResponseSuccess(ctx, msg, promise, inboundMessageContext);
                } else {
                    ctx.writeAndFlush(new TextWebSocketFrame("Websocket frame throttled out"));
                    if (log.isDebugEnabled()) {
                        log.debug("Outbound Websocket frame is throttled. " + ctx.channel().toString());
                    }
                }
            }
        } else {
            outboundHandler().write(ctx, msg, promise);
        }
    }

    /**
     * @param ctx                   ChannelHandlerContext
     * @param msg                   Message
     * @param promise               ChannelPromise
     * @param inboundMessageContext InboundMessageContext
     * @throws Exception
     */
    private void handleWSResponseSuccess(ChannelHandlerContext ctx, Object msg, ChannelPromise promise,
            InboundMessageContext inboundMessageContext) throws Exception {
        outboundHandler().write(ctx, msg, promise);
        // publish analytics events if analytics is enabled
        if (APIUtil.isAnalyticsEnabled()) {
            String clientIp = getClientIp(ctx);
            WebsocketUtil.publishWSRequestEvent(clientIp, true, inboundMessageContext,
                    inboundHandler().getUsageDataPublisher());
        }
    }

    protected boolean isAllowed(ChannelHandlerContext ctx, WebSocketFrame msg,
                                InboundMessageContext inboundMessageContext,
                                APIMgtUsageDataPublisher usageDataPublisher) {
        WebSocketThrottleResponseDTO webSocketThrottleResponseDTO =
                WebsocketUtil.doThrottle(ctx, msg, null, inboundMessageContext);
        if (webSocketThrottleResponseDTO.isThrottled()) {
            if (APIUtil.isAnalyticsEnabled()) {
                WebsocketUtil.publishWSThrottleEvent(inboundMessageContext, usageDataPublisher,
                        webSocketThrottleResponseDTO.getThrottledOutReason());
            }
        }
        return !webSocketThrottleResponseDTO.isThrottled();
    }

    protected String getClientIp(ChannelHandlerContext ctx) {
        return WebsocketUtil.getRemoteIP(ctx);
    }
}
