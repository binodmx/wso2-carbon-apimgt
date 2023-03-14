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
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.InboundMessageContextDataHolder;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLResponseProcessor;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;

import java.util.HashMap;

public class WebsocketHandler extends CombinedChannelDuplexHandler<WebsocketInboundHandler, WebsocketOutboundHandler> {

    private static final Log log = LogFactory.getLog(WebsocketInboundHandler.class);

    public WebsocketHandler() {
        super(new WebsocketInboundHandler(), new WebsocketOutboundHandler());
    }

    private static GraphQLResponseProcessor graphQLResponseProcessor = new GraphQLResponseProcessor();
    private final String API_PROPERTIES = "API_PROPERTIES";
    private final String WEB_SC_API_UT = "api.ut.WS_SC";

    public WebsocketHandler(WebsocketInboundHandler websocketInboundHandler,
                            WebsocketOutboundHandler websocketOutboundHandler) {
        super(websocketInboundHandler, websocketOutboundHandler);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        ctx.channel().attr(AttributeKey.valueOf(APIMgtGatewayConstants.RESPONSE_START_TIME)).set(System
                .currentTimeMillis());
        String channelId = ctx.channel().id().asLongText();
        InboundMessageContext inboundMessageContextN = new InboundMessageContext();
        InboundMessageContext inboundMessageContext = InboundMessageContextDataHolder.getInstance()
                .putIfAbsentInboundMessageContextForConnection(channelId, inboundMessageContextN);
        if (inboundMessageContext == null) {
            inboundMessageContext = inboundMessageContextN;
        }

        if (msg instanceof CloseWebSocketFrame) {
            if (((CloseWebSocketFrame) msg).statusCode() > 1001) {
                log.info(channelId + " -- ERROR_CODE = " + ((CloseWebSocketFrame) msg).statusCode()
                        + ", ERROR_MESSAGE = " + ((CloseWebSocketFrame) msg).reasonText());
                if (APIUtil.isAnalyticsEnabled()) {
                    WebsocketUtil.publishFaultEvent((CloseWebSocketFrame) msg, inboundMessageContext,
                            inboundHandler().getUsageDataPublisher());
                }
            }

            Attribute<Object> attributes = ctx.channel().attr(AttributeKey.valueOf(API_PROPERTIES));
            if (attributes != null) {
                HashMap apiProperties = (HashMap) attributes.get();
                apiProperties.put(WEB_SC_API_UT, ((CloseWebSocketFrame) msg).statusCode());
            }

            //remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().remove(channelId);
            //if the inbound frame is a closed frame, throttling, analytics will not be published.
            outboundHandler().write(ctx, msg, promise);

        } else if (msg instanceof PongWebSocketFrame || msg instanceof PingWebSocketFrame) {
            //if the inbound frame is a ping/pong frame, throttling, analytics will not be published.
            outboundHandler().write(ctx, msg, promise);
        } else if (msg instanceof WebSocketFrame) {
            InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
            if (APIConstants.APITransportType.GRAPHQL.toString()
                    .equals(inboundMessageContext.getElectedAPI().getApiType()) && msg instanceof TextWebSocketFrame) {
                // Authenticate and handle GraphQL subscription responses
                responseDTO = graphQLResponseProcessor.handleResponse((WebSocketFrame) msg,
                        ctx, inboundMessageContext, inboundHandler().getUsageDataPublisher());
                if (responseDTO.isError()) {
                    handleWebsocketFrameRequestError(responseDTO, channelId, ctx, promise, msg);
                    if (APIUtil.isAnalyticsEnabled()) {
                        WebsocketUtil.publishFaultEvent(responseDTO, inboundMessageContext,
                                inboundHandler().getUsageDataPublisher());
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug(channelId + " -- Websocket API request [outbound] : Sending Outbound Websocket frame." + ctx.channel().toString());
                    }
                    outboundHandler().write(ctx, msg, promise);
                }
            } else {
                responseDTO = inboundMessageContext.isJWTToken() ?
                        WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext) :
                        WebsocketUtil.authenticateOAuthToken(responseDTO, inboundMessageContext.getApiKey(),
                                inboundMessageContext);
                if (!responseDTO.isError()) {
                    // Validate the deny policies are applied to the API when there are no authentication errors
                    responseDTO = WebsocketUtil.validateDenyPolicies(inboundMessageContext,
                            inboundHandler().getUsageDataPublisher());
                    // Check whether the error is now present after deny policies validation
                    if (responseDTO.isError()) {
                        handleWebsocketFrameRequestError(responseDTO, channelId, ctx, promise, msg);
                    } else if (isAllowed(ctx, (WebSocketFrame) msg, inboundMessageContext,
                            inboundHandler().getUsageDataPublisher())) {
                        handleWSResponseSuccess(ctx, msg, promise, inboundMessageContext);
                    } else {
                        ReferenceCountUtil.release(msg);
                        ctx.writeAndFlush(new TextWebSocketFrame("Websocket frame throttled out"));
                        if (log.isDebugEnabled()) {
                            log.debug(channelId + " -- Websocket API request [outbound] : Outbound Websocket frame is throttled. " + ctx.channel().toString());
                        }
                    }
                } else {
                    handleWebsocketFrameRequestError(responseDTO, channelId, ctx, promise, msg);
                    if (APIUtil.isAnalyticsEnabled()) {
                        WebsocketUtil.publishFaultEvent(responseDTO, inboundMessageContext,
                                inboundHandler().getUsageDataPublisher());
                    }
                }
            }
        } else {
            outboundHandler().write(ctx, msg, promise);
        }
    }

    /**
     * @param responseDTO InboundProcessorResponseDTO
     * @param channelId   Channel Id of the web socket connection
     * @param ctx         ChannelHandlerContext
     * @param msg         WebsocketFrame that was received
     */
    private void handleWebsocketFrameRequestError(InboundProcessorResponseDTO responseDTO, String channelId,
                                                  ChannelHandlerContext ctx, ChannelPromise promise, Object msg)
            throws Exception {
        // Release WebsocketFrame
        ReferenceCountUtil.release(msg);
        if (responseDTO.isCloseConnection()) {
            // remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().removeInboundMessageContextForConnection(channelId);
            if (log.isDebugEnabled()) {
                log.debug(channelId + " -- Websocket API request [outbound] : Error while handling Outbound Websocket frame. Closing connection for "
                        + ctx.channel().toString());
            }
            outboundHandler().write(ctx, new CloseWebSocketFrame(responseDTO.getErrorCode(),
                    responseDTO.getErrorMessage() + StringUtils.SPACE + "Connection closed" + "!"), promise);
            outboundHandler().flush(ctx);
            outboundHandler().close(ctx, promise);
        } else {
            String errorMessage = responseDTO.getErrorResponseString();
            outboundHandler().write(ctx, new TextWebSocketFrame(errorMessage), promise);
            if (responseDTO.getErrorCode() == WebSocketApiConstants.FrameErrorConstants.THROTTLED_OUT_ERROR) {
                if (log.isDebugEnabled()) {
                    log.debug(channelId + " -- Websocket API request [outbound] : Outbound Websocket frame is throttled. " + ctx.channel().toString());
                }
            }
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
        long endTime = System.currentTimeMillis();
        long startTime = (long) ctx.channel().attr(AttributeKey.valueOf(APIMgtGatewayConstants.RESPONSE_START_TIME))
                .get();
        long serviceTime = endTime - startTime;
        if (log.isDebugEnabled()) {
            log.debug(ctx.channel().id().asLongText() + " -- Websocket API request [outbound] : Sending Outbound Websocket frame." + ctx.channel().toString());
        }
        outboundHandler().write(ctx, msg, promise);
        // publish analytics events if analytics is enabled
        if (APIUtil.isAnalyticsEnabled()) {
            String clientIp = getClientIp(ctx);
            WebsocketUtil.publishWSRequestEvent(clientIp, true, inboundMessageContext,
                    inboundHandler().getUsageDataPublisher(), serviceTime);
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
