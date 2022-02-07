/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.t
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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.apimgt.gateway.dto.GraphQLOperationDTO;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.context.PrivilegedCarbonContext;

import java.util.UUID;

/**
 * Test class for GraphQLResponseProcessor.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ PrivilegedCarbonContext.class, GraphQLProcessor.class })
public class GraphQLResponseProcessorTest {

    private ChannelHandlerContext channelHandlerContext;
    private APIMgtUsageDataPublisher usageDataPublisher;
    private API graphQLAPI;
    private GraphQLResponseProcessor graphQLResponseProcessor;

    @Before
    public void setup() {
        channelHandlerContext = Mockito.mock(ChannelHandlerContext.class);
        usageDataPublisher = Mockito.mock(APIMgtUsageDataPublisher.class);
        PowerMockito.mockStatic(PrivilegedCarbonContext.class);
        PrivilegedCarbonContext privilegedCarbonContext = Mockito.mock(PrivilegedCarbonContext.class);
        PowerMockito.when(PrivilegedCarbonContext.getThreadLocalCarbonContext()).thenReturn(privilegedCarbonContext);
        PowerMockito.mockStatic(GraphQLProcessor.class);
        graphQLAPI = new API(UUID.randomUUID().toString(), 2, "admin", "GraphQLAPI", "1.0.0", "/graphql", "Unlimited",
                APIConstants.GRAPHQL_API, false);
        graphQLResponseProcessor = new GraphQLResponseProcessor();
    }

    @Test
    public void testHandleResponseSuccess() throws APISecurityException {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"data\",\"id\":\"1\",\"payload\":{\"data\":"
                + "{\"liftStatusChange\":{\"name\":\"Astra Express\"}}}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(responseDTO);

        VerbInfoDTO verbInfoDTO = new VerbInfoDTO();
        verbInfoDTO.setHttpVerb("SUBSCRIPTION");
        verbInfoDTO.setThrottling("Unlimited");
        GraphQLOperationDTO graphQLOperationDTO = new GraphQLOperationDTO(verbInfoDTO, "liftStatusChange");
        inboundMessageContext.addVerbInfoForGraphQLMsgId("1", graphQLOperationDTO);

        PowerMockito.when(GraphQLProcessor.doThrottleForGraphQL(msg, channelHandlerContext, verbInfoDTO,
                inboundMessageContext, "1", usageDataPublisher)).thenReturn(responseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLResponseProcessor.handleResponse(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertFalse(processorResponseDTO.isError());
        Assert.assertNull(processorResponseDTO.getErrorMessage());
    }

    @Test
    public void testHandleNonSubscribeResponse() throws APISecurityException {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"connection_ack\"}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLResponseProcessor.handleResponse(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertFalse(processorResponseDTO.isError());
        Assert.assertNull(processorResponseDTO.getErrorMessage());
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
    }

    @Test
    public void testHandleBadResponse() throws APISecurityException {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"data\",\"payload\":{\"data\":"
                + "{\"liftStatusChange\":{\"name\":\"Astra Express\"}}}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BAD_REQUEST);
        inboundProcessorResponseDTO.setErrorMessage("Missing mandatory id field in the message");
        PowerMockito.when(GraphQLProcessor.getBadRequestGraphQLFrameErrorDTO("Missing mandatory id field in"
                        + " the message", null)).thenReturn(inboundProcessorResponseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLResponseProcessor.handleResponse(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertNotNull(processorResponseDTO.getErrorMessage());
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
        Assert.assertEquals(processorResponseDTO.getErrorResponseString(),
                inboundProcessorResponseDTO.getErrorResponseString());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(), inboundProcessorResponseDTO.getErrorMessage());
        Assert.assertEquals(processorResponseDTO.getErrorCode(), inboundProcessorResponseDTO.getErrorCode());
    }

    @Test
    public void testHandleThrottleOut() throws APISecurityException {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"data\",\"id\":\"1\",\"payload\":{\"data\":"
                + "{\"liftStatusChange\":{\"name\":\"Astra Express\"}}}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        VerbInfoDTO verbInfoDTO = new VerbInfoDTO();
        verbInfoDTO.setHttpVerb("SUBSCRIPTION");
        verbInfoDTO.setThrottling("Unlimited");
        GraphQLOperationDTO graphQLOperationDTO = new GraphQLOperationDTO(verbInfoDTO, "liftStatusChange");
        inboundMessageContext.addVerbInfoForGraphQLMsgId("1", graphQLOperationDTO);
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(responseDTO);
        InboundProcessorResponseDTO throttleResponseDTO = new InboundProcessorResponseDTO();
        throttleResponseDTO.setError(true);
        throttleResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR);
        throttleResponseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE);
        throttleResponseDTO.setId("1");
        PowerMockito.when(GraphQLProcessor.doThrottleForGraphQL(msg, channelHandlerContext, verbInfoDTO,
                inboundMessageContext, "1", usageDataPublisher)).thenReturn(throttleResponseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLResponseProcessor.handleResponse(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertNotNull(processorResponseDTO.getErrorMessage());
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
        Assert.assertEquals(processorResponseDTO.getErrorResponseString(),
                throttleResponseDTO.getErrorResponseString());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(), throttleResponseDTO.getErrorMessage());
        Assert.assertEquals(processorResponseDTO.getErrorCode(), throttleResponseDTO.getErrorCode());
    }

    @Test
    public void testHandleInvalidScope() throws APISecurityException {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"data\",\"id\":\"1\",\"payload\":{\"data\":"
                + "{\"liftStatusChange\":{\"name\":\"Astra Express\"}}}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        VerbInfoDTO verbInfoDTO = new VerbInfoDTO();
        verbInfoDTO.setHttpVerb("SUBSCRIPTION");
        verbInfoDTO.setThrottling("Unlimited");
        GraphQLOperationDTO graphQLOperationDTO = new GraphQLOperationDTO(verbInfoDTO, "liftStatusChange");
        inboundMessageContext.addVerbInfoForGraphQLMsgId("1", graphQLOperationDTO);
        InboundProcessorResponseDTO graphQLProcessorResponseDTO = new InboundProcessorResponseDTO();
        graphQLProcessorResponseDTO.setError(true);
        graphQLProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR);
        graphQLProcessorResponseDTO.setErrorMessage("User is NOT authorized to access the Resource");
        graphQLProcessorResponseDTO.setCloseConnection(false);
        graphQLProcessorResponseDTO.setId("1");
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(graphQLProcessorResponseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLResponseProcessor.handleResponse(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertNotNull(processorResponseDTO.getErrorMessage());
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
        Assert.assertEquals(processorResponseDTO.getErrorResponseString(),
                graphQLProcessorResponseDTO.getErrorResponseString());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(), graphQLProcessorResponseDTO.getErrorMessage());
        Assert.assertEquals(processorResponseDTO.getErrorCode(), graphQLProcessorResponseDTO.getErrorCode());
    }

    private InboundMessageContext createApiMessageContext(API api) {
        InboundMessageContext inboundMessageContext = new InboundMessageContext();
        inboundMessageContext.setTenantDomain("carbon.super");
        inboundMessageContext.setElectedAPI(api);
        inboundMessageContext.setJWTToken(true);
        return inboundMessageContext;
    }
}
