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
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import org.wso2.carbon.apimgt.gateway.handlers.WebsocketUtil;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.context.PrivilegedCarbonContext;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ PrivilegedCarbonContext.class, WebsocketUtil.class, ServiceReferenceHolder.class })
public class GraphQLProcessorTest {

    private ChannelHandlerContext channelHandlerContext;
    private APIMgtUsageDataPublisher usageDataPublisher;
    private ServiceReferenceHolder serviceReferenceHolder;
    private APIManagerConfiguration apiManagerConfiguration;
    private VerbInfoDTO verbInfoDTO;
    private String msgText;
    private TextWebSocketFrame msg;
    private String operationId;

    @Before
    public void setup() {
        System.setProperty("carbon.home", "test");
        channelHandlerContext = Mockito.mock(ChannelHandlerContext.class);
        usageDataPublisher = Mockito.mock(APIMgtUsageDataPublisher.class);
        PowerMockito.mockStatic(PrivilegedCarbonContext.class);
        PrivilegedCarbonContext privilegedCarbonContext = Mockito.mock(PrivilegedCarbonContext.class);
        PowerMockito.when(PrivilegedCarbonContext.getThreadLocalCarbonContext()).thenReturn(privilegedCarbonContext);
        PowerMockito.mockStatic(ServiceReferenceHolder.class);
        serviceReferenceHolder = Mockito.mock(ServiceReferenceHolder.class);
        apiManagerConfiguration = Mockito.mock(APIManagerConfiguration.class);
        PowerMockito.when(ServiceReferenceHolder.getInstance()).thenReturn(serviceReferenceHolder);
        PowerMockito.when(serviceReferenceHolder.getAPIManagerConfiguration()).thenReturn(apiManagerConfiguration);
        PowerMockito.mockStatic(WebsocketUtil.class);
        verbInfoDTO = Mockito.mock(VerbInfoDTO.class);
        msgText = "{\"type\":\"data\",\"id\":\"1\",\"payload\":{\"data\":"
                + "{\"liftStatusChange\":{\"name\":\"Astra Express\"}}}}";
        msg = new TextWebSocketFrame(msgText);
        operationId = "1";
    }

    @Test
    public void testDoThrottleForGraphQLSuccess() {

        InboundMessageContext inboundMessageContext = new InboundMessageContext();
        PowerMockito.when(WebsocketUtil.doThrottle(channelHandlerContext, msg, verbInfoDTO, inboundMessageContext,
                usageDataPublisher)).thenReturn(true);
        InboundProcessorResponseDTO inboundProcessorResponseDTO = GraphQLProcessor.doThrottleForGraphQL(msg,
                channelHandlerContext, verbInfoDTO, inboundMessageContext, operationId, usageDataPublisher);
        Assert.assertFalse(inboundProcessorResponseDTO.isError());
        Assert.assertNull(inboundProcessorResponseDTO.getErrorMessage());
        Assert.assertFalse(inboundProcessorResponseDTO.isCloseConnection());
    }

    @Test
    public void testDoThrottleFail() throws ParseException {
        InboundMessageContext inboundMessageContext = new InboundMessageContext();
        PowerMockito.when(WebsocketUtil.doThrottle(channelHandlerContext, msg, verbInfoDTO, inboundMessageContext,
                usageDataPublisher)).thenReturn(false);
        InboundProcessorResponseDTO inboundProcessorResponseDTO = GraphQLProcessor.doThrottleForGraphQL(msg,
                channelHandlerContext, verbInfoDTO, inboundMessageContext, operationId, usageDataPublisher);
        Assert.assertTrue(inboundProcessorResponseDTO.isError());
        Assert.assertEquals(inboundProcessorResponseDTO.getErrorMessage(),
                GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE);
        Assert.assertEquals(inboundProcessorResponseDTO.getErrorCode(),
                GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR);
        Assert.assertFalse(inboundProcessorResponseDTO.isCloseConnection());

        JSONParser jsonParser = new JSONParser();
        JSONObject errorJson = (JSONObject) jsonParser.parse(inboundProcessorResponseDTO.getErrorResponseString());
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        JSONObject payload = (JSONObject) errorJson.get(
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertEquals(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE),
                GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE);
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR));
    }
}
