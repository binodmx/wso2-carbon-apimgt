/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.apimgt.gateway.graphQL;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.apache.axis2.AxisFault;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.gateway.dto.GraphQLOperationDTO;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import org.wso2.carbon.apimgt.gateway.handlers.WebsocketUtil;
import org.wso2.carbon.apimgt.gateway.handlers.security.APIKeyValidator;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.jwt.JWTValidator;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;

public abstract class GraphQLProcessor {

    private static final Log log = LogFactory.getLog(GraphQLProcessor.class);

    /**
     * Authenticates JWT token in incoming GraphQL subscription requests.
     *
     * @param inboundMessageContext InboundMessageContext
     * @return InboundProcessorResponseDTO
     */
    public static InboundProcessorResponseDTO authenticateGraphQLJWTToken(InboundMessageContext inboundMessageContext) {

        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        AuthenticationContext authenticationContext;
        JWTValidator jwtValidator = new JWTValidator(new APIKeyValidator());
        try {
            authenticationContext = jwtValidator.authenticateForWSAndGraphQL(inboundMessageContext.getSignedJWTInfo(),
                    inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion());
            inboundMessageContext.setAuthContext(authenticationContext);
            if (!WebsocketUtil.validateAuthenticationContext(inboundMessageContext,
                    inboundMessageContext.getElectedAPI().isDefaultVersion())) {
                responseDTO = getFrameErrorDTO(GraphQLConstants.FrameErrorConstants.API_AUTH_INVALID_CREDENTIALS,
                        APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE, true);
            }
        } catch (APISecurityException e) {
            log.error(GraphQLConstants.FrameErrorConstants.API_AUTH_INVALID_CREDENTIALS, e);
            responseDTO = getFrameErrorDTO(GraphQLConstants.FrameErrorConstants.API_AUTH_INVALID_CREDENTIALS,
                    e.getMessage(), true);
        }
        return responseDTO;
    }

    /**
     * Authenticates OAuth token in incoming GraphQL subscription requests.
     *
     * @param responseDTO           InboundProcessorResponseDTO
     * @param inboundMessageContext InboundMessageContext
     * @return InboundProcessorResponseDTO
     * @throws APISecurityException if an error occurs while retrieving API key data for client
     */
    public static InboundProcessorResponseDTO authenticateGraphQLOAuthToken(InboundProcessorResponseDTO responseDTO,
            InboundMessageContext inboundMessageContext) throws APISecurityException {

        return WebsocketUtil.authenticateOAuthToken(responseDTO, inboundMessageContext.getApiKey(),
                inboundMessageContext);
    }

    /**
     * Validates scopes for subscription operations.
     *
     * @param inboundMessageContext InboundMessageContext
     * @param subscriptionOperation Subscription operation
     * @param operationId           GraphQL message Id
     * @return InboundProcessorResponseDTO
     */
    public static InboundProcessorResponseDTO validateScopes(InboundMessageContext inboundMessageContext, String subscriptionOperation, String operationId) {

        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        // validate scopes based on subscription payload
        try {
            if (!authorizeGraphQLSubscriptionEvents(inboundMessageContext, subscriptionOperation)) {
                String errorMessage =
                        GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR_MESSAGE + StringUtils.SPACE
                                + subscriptionOperation;
                log.error(errorMessage);
                responseDTO = getGraphQLFrameErrorDTO(GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR,
                        errorMessage, false, operationId);
            }
        } catch (APISecurityException e) {
            log.error(GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR_MESSAGE, e);
            responseDTO = getGraphQLFrameErrorDTO(GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR,
                    e.getMessage(), false, operationId);
        }
        return responseDTO;
    }

    /**
     * Validate scopes of JWT token for incoming GraphQL subscription messages.
     *
     * @param inboundMessageContext InboundMessageContext
     * @param matchingResource      Invoking GraphQL subscription operation
     * @return true if authorized
     * @throws APISecurityException If authorization fails
     */
    private static boolean authorizeGraphQLSubscriptionEvents(InboundMessageContext inboundMessageContext,
            String matchingResource) throws APISecurityException {

        JWTValidator jwtValidator = new JWTValidator(new APIKeyValidator());
        jwtValidator.validateScopesForGraphQLSubscriptions(inboundMessageContext.getApiContextUri(),
                inboundMessageContext.getVersion(), matchingResource, inboundMessageContext.getSignedJWTInfo(),
                inboundMessageContext.getAuthContext());
        return true;
    }

    /**
     * Get error frame DTO for error code and message closeConnection parameters.
     *
     * @param errorCode       Error code
     * @param errorMessage    Error message
     * @param closeConnection Whether to close connection after throwing the error frame
     * @return InboundProcessorResponseDTO
     */
    private static InboundProcessorResponseDTO getFrameErrorDTO(int errorCode, String errorMessage,
            boolean closeConnection) {

        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(errorCode);
        inboundProcessorResponseDTO.setErrorMessage(errorMessage);
        inboundProcessorResponseDTO.setCloseConnection(closeConnection);
        return inboundProcessorResponseDTO;
    }

    /**
     * Get GraphQL subscription error frame DTO for error code and message closeConnection parameters.
     *
     * @param errorCode       Error code
     * @param errorMessage    Error message
     * @param closeConnection Whether to close connection after throwing the error frame
     * @param operationId     Operation ID
     * @return InboundProcessorResponseDTO
     */
    private static InboundProcessorResponseDTO getGraphQLFrameErrorDTO(int errorCode, String errorMessage,
            boolean closeConnection, String operationId) {

        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(errorCode);
        inboundProcessorResponseDTO.setErrorMessage(errorMessage);
        inboundProcessorResponseDTO.setCloseConnection(closeConnection);
        inboundProcessorResponseDTO.setId(operationId);
        return inboundProcessorResponseDTO;
    }

    /**
     * Get GraphQL Subscriptions handshake error DTO for error code and message. The closeConnection parameter is false.
     *
     * @param errorCode    Error code
     * @param errorMessage Error message
     * @return InboundProcessorResponseDTO
     */
    public static InboundProcessorResponseDTO getHandshakeErrorDTO(int errorCode, String errorMessage) {

        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(errorCode);
        inboundProcessorResponseDTO.setErrorMessage(errorMessage);
        return inboundProcessorResponseDTO;
    }

    /**
     * Checks if the request is throttled for GraphQL subscriptions.
     *
     * @param msg                   Websocket frame
     * @param ctx                   Channel handler context
     * @param verbInfoDTO           InboundMessageContext
     * @param inboundMessageContext VerbInfoDTO for invoking operation
     * @param operationId           Operation ID
     * @param usageDataPublisher    APIMgtUsageDataPublisher
     * @return InboundProcessorResponseDTO
     */
    public static InboundProcessorResponseDTO doThrottleForGraphQL(WebSocketFrame msg, ChannelHandlerContext ctx,
            VerbInfoDTO verbInfoDTO, InboundMessageContext inboundMessageContext, String operationId,
            APIMgtUsageDataPublisher usageDataPublisher) {

        String operationName = null;
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        responseDTO.setId(operationId);
        WebSocketThrottleResponseDTO throttleResponseDTO =
                WebsocketUtil.doThrottle(ctx, msg, verbInfoDTO, inboundMessageContext);

        if (throttleResponseDTO.isThrottled()) {
            GraphQLOperationDTO graphQLOperationDTO = inboundMessageContext.getVerbInfoForGraphQLMsgId(operationId);
            if (graphQLOperationDTO != null) {
                operationName = graphQLOperationDTO.getOperation();
            }
            if (APIUtil.isAnalyticsEnabled()) {
                WebsocketUtil.publishGraphQLSubThrottleEvent(inboundMessageContext, usageDataPublisher, operationName,
                        throttleResponseDTO.getThrottledOutReason());
            }
            responseDTO.setError(true);
            responseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR);
            responseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE);
        } else {
            return responseDTO;
        }
        return responseDTO;
    }

    /**
     * Get bad request (error code 4010) error frame DTO for GraphQL subscriptions. The closeConnection parameter is
     * false.
     *
     * @param errorMessage Error message
     * @param operationId  Operation ID
     * @return InboundProcessorResponseDTO
     */
    public static InboundProcessorResponseDTO getBadRequestGraphQLFrameErrorDTO(String errorMessage,
            String operationId) {

        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BAD_REQUEST);
        inboundProcessorResponseDTO.setErrorMessage(errorMessage);
        if (StringUtils.isNotBlank(operationId)) {
            inboundProcessorResponseDTO.setId(operationId);
        }
        return inboundProcessorResponseDTO;
    }
}
