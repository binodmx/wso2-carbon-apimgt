/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.carbon.apimgt.gateway.graphQL;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.json.JSONObject;
import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import org.wso2.carbon.apimgt.gateway.dto.GraphQLOperationDTO;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.handlers.WebsocketUtil;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.context.PrivilegedCarbonContext;

/**
 * A GraphQL subscriptions specific extension of ResponseProcessor. This class intercepts the inbound websocket
 * execution path of graphQL subscription responses (subscribe messages).
 */
public class GraphQLResponseProcessor extends GraphQLProcessor {

    /**
     * Handle inbound websocket responses of GraphQL subscriptions and perform authentication, authorization
     * and throttling. This identifies operation from the subscription responses using the unique message id parameter.
     *
     * @param msg                   graphQL subscription response payload
     * @param ctx                   ChannelHandlerContext
     * @param inboundMessageContext InboundMessageContext
     * @param usageDataPublisher    APIMgtUsageDataPublisher
     * @return InboundProcessorResponseDTO
     */
    public InboundProcessorResponseDTO handleResponse(WebSocketFrame msg, ChannelHandlerContext ctx,
            InboundMessageContext inboundMessageContext, APIMgtUsageDataPublisher usageDataPublisher)
            throws APISecurityException {

        String subscriptionOperation = null;
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        try {
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext()
                    .setTenantDomain(inboundMessageContext.getTenantDomain(), true);
            responseDTO = inboundMessageContext.isJWTToken() ?
                    authenticateGraphQLJWTToken(inboundMessageContext) :
                    authenticateGraphQLOAuthToken(responseDTO, inboundMessageContext);

            String msgText = ((TextWebSocketFrame) msg).text();
            JSONObject graphQLMsg = new JSONObject(msgText);

            if (!responseDTO.isError() && checkIfSubscribeMessageResponse(graphQLMsg)) {
                if (graphQLMsg.has(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID)
                        && graphQLMsg.getString(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID) != null) {
                    String operationId = graphQLMsg.getString(
                            GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID);
                    GraphQLOperationDTO graphQLOperationDTO = inboundMessageContext.getVerbInfoForGraphQLMsgId(
                            graphQLMsg.getString(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID));
                    subscriptionOperation = graphQLOperationDTO.getOperation();
                    // validate scopes based on subscription payload
                    responseDTO = validateScopes(inboundMessageContext, graphQLOperationDTO.getOperation(),
                            operationId);
                    if (!responseDTO.isError()) {
                        // throttle for matching resource
                        responseDTO = doThrottleForGraphQL(msg, ctx, graphQLOperationDTO.getVerbInfoDTO(),
                                inboundMessageContext, operationId, usageDataPublisher);
                    }
                } else {
                    responseDTO = getBadRequestGraphQLFrameErrorDTO("Missing mandatory id field in the message", null);
                }
                if (!responseDTO.isError()) {
                    // publish analytics events if analytics is enabled
                    if (APIUtil.isAnalyticsEnabled()) {
                        WebsocketUtil.publishGraphQLSubscriptionEvent(inboundMessageContext.getUserIP(), true,
                                inboundMessageContext, usageDataPublisher, subscriptionOperation);
                    }
                }
            }
            return responseDTO;
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    /**
     * Check if messages is valid subscription operation execution result. Payload should consist 'type' field and its
     * value equal to either of 'data' or 'next'. The value 'data' is used in 'subscriptions-transport-ws'
     * protocol and 'next' is used in 'graphql-ws' protocol.
     *
     * @param graphQLMsg GraphQL message
     * @return true if valid operation
     */
    private boolean checkIfSubscribeMessageResponse(JSONObject graphQLMsg) {
        return graphQLMsg.getString(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE) != null
                && GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ARRAY_FOR_DATA.contains(
                graphQLMsg.getString(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE));
    }
}
