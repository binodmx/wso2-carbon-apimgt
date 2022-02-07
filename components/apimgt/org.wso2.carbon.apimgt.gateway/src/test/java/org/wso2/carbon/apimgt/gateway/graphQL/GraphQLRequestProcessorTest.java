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

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.apache.axis2.AxisFault;
import org.apache.commons.io.IOUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.apimgt.api.gateway.GraphQLSchemaDTO;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.internal.DataHolder;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.ResourceInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.context.PrivilegedCarbonContext;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test class for GraphQLRequestProcessor.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({ PrivilegedCarbonContext.class, GraphQLProcessorUtil.class, GraphQLProcessor.class
        , DataHolder.class, APIUtil.class })
public class GraphQLRequestProcessorTest {

    private ChannelHandlerContext channelHandlerContext;
    private APIMgtUsageDataPublisher usageDataPublisher;
    private DataHolder dataHolder;
    private API graphQLAPI;
    private GraphQLRequestProcessor graphQLRequestProcessor;

    @Before
    public void setup() {
        System.setProperty("carbon.home", "test");
        channelHandlerContext = Mockito.mock(ChannelHandlerContext.class);
        usageDataPublisher = Mockito.mock(APIMgtUsageDataPublisher.class);
        PowerMockito.mockStatic(PrivilegedCarbonContext.class);
        PrivilegedCarbonContext privilegedCarbonContext = Mockito.mock(PrivilegedCarbonContext.class);
        PowerMockito.when(PrivilegedCarbonContext.getThreadLocalCarbonContext()).thenReturn(privilegedCarbonContext);
        PowerMockito.mockStatic(GraphQLProcessorUtil.class);
        PowerMockito.mockStatic(GraphQLProcessor.class);
        PowerMockito.mockStatic(DataHolder.class);
        dataHolder = Mockito.mock(DataHolder.class);
        PowerMockito.when(DataHolder.getInstance()).thenReturn(dataHolder);
        PowerMockito.mockStatic(APIUtil.class);
        graphQLAPI = new API(UUID.randomUUID().toString(), 2, "admin", "GraphQLAPI", "1.0.0", "/graphql", "Unlimited",
                APIConstants.GRAPHQL_API, false);
        graphQLRequestProcessor = new GraphQLRequestProcessor();
    }

    @Test
    public void testHandleRequestSuccess() throws Exception {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null,\"query\":\"subscription {\\n  "
                + "liftStatusChange {\\n    id\\n    name\\n    }\\n}\\n\"}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(responseDTO);

        // Get schema and parse
        String graphqlDirPath = "graphQL" + File.separator;
        String relativePath = graphqlDirPath + "schema_with_additional_props.graphql";
        String schemaString = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath));
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry registry = schemaParser.parse(schemaString);
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        GraphQLSchemaDTO schemaDTO = new GraphQLSchemaDTO(schema, registry);
        PowerMockito.when(DataHolder.getInstance().getGraphQLSchemaDTOForAPI(
                inboundMessageContext.getElectedAPI().getUuid())).thenReturn(schemaDTO);
        PowerMockito.when(GraphQLProcessorUtil.getOperationList(Mockito.anyObject(), Mockito.anyObject(),
                        Mockito.anyString())).thenReturn("liftStatusChange");

        VerbInfoDTO verbInfoDTO = new VerbInfoDTO();
        verbInfoDTO.setHttpVerb("SUBSCRIPTION");
        verbInfoDTO.setThrottling("Unlimited");
        Set<VerbInfoDTO> verbInfoDTOS = new HashSet<>();
        verbInfoDTOS.add(verbInfoDTO);
        ResourceInfoDTO resourceInfoDTO = new ResourceInfoDTO();
        resourceInfoDTO.setHttpVerbs(verbInfoDTOS);
        resourceInfoDTO.setUrlPattern("liftStatusChange");
        Map<String, ResourceInfoDTO> resourcesMap = new HashMap<>();
        resourcesMap.put("liftStatusChange", resourceInfoDTO);
        inboundMessageContext.setResourcesMap(resourcesMap);
        APIKeyValidationInfoDTO infoDTO = new APIKeyValidationInfoDTO();
        infoDTO.setGraphQLMaxComplexity(4);
        infoDTO.setGraphQLMaxDepth(3);
        inboundMessageContext.setInfoDTO(infoDTO);
        PowerMockito.when(APIUtil.getResourceInfoDTOCacheKey(inboundMessageContext.getElectedAPI().getContext(),
                inboundMessageContext.getElectedAPI().getApiVersion(), "liftStatusChange",
                GraphQLConstants.SubscriptionConstants.HTTP_METHOD_NAME)).thenReturn("");

        PowerMockito.when(GraphQLProcessor.doThrottleForGraphQL(msg, channelHandlerContext, verbInfoDTO,
                inboundMessageContext, "1", usageDataPublisher)).thenReturn(responseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertFalse(processorResponseDTO.isError());
        Assert.assertNull(processorResponseDTO.getErrorMessage());
        Assert.assertEquals(inboundMessageContext.getVerbInfoForGraphQLMsgId("1").getOperation(), "liftStatusChange");
        Assert.assertEquals(inboundMessageContext.getVerbInfoForGraphQLMsgId("1").getVerbInfoDTO().getHttpVerb(),
                "SUBSCRIPTION");
        Assert.assertEquals(inboundMessageContext.getVerbInfoForGraphQLMsgId("1").getVerbInfoDTO().getThrottling(),
                "Unlimited");
    }

    @Test
    public void testHandleRequestNonSubscribeMessage() throws APISecurityException, AxisFault {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"connection_init\",\"payload\":{}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);

        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertFalse(processorResponseDTO.isError());
        Assert.assertNull(processorResponseDTO.getErrorMessage());
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
    }

    @Test
    public void testHandleRequestAuthError() throws APISecurityException, AxisFault {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"type\":\"connection_init\",\"payload\":{}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        responseDTO.setError(true);
        responseDTO.setErrorMessage("Invalid authentication");
        responseDTO.setCloseConnection(true);
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);

        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(), "Invalid authentication");
        Assert.assertTrue(responseDTO.isCloseConnection());
    }

    @Test
    public void testHandleRequestInvalidPayload() throws Exception {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);
        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BAD_REQUEST);
        inboundProcessorResponseDTO.setErrorMessage("Invalid operation payload");
        inboundProcessorResponseDTO.setId("1");
        PowerMockito.when(GraphQLProcessor.getBadRequestGraphQLFrameErrorDTO("Invalid operation payload", "1"))
                .thenReturn(inboundProcessorResponseDTO);

        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(), "Invalid operation payload");
        Assert.assertEquals(processorResponseDTO.getErrorCode(), GraphQLConstants.FrameErrorConstants.BAD_REQUEST);
        Assert.assertNotNull(processorResponseDTO.getErrorResponseString());
        JSONParser jsonParser = new JSONParser();
        JSONObject errorJson = (JSONObject) jsonParser.parse(processorResponseDTO.getErrorResponseString());
        Assert.assertTrue(errorJson.containsKey(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE));
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertTrue(errorJson.containsKey(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID));
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        Assert.assertTrue(errorJson.containsKey(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD));
        JSONObject payload = (JSONObject) errorJson.get(
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertTrue(payload.containsKey(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE));
        Assert.assertTrue(payload.containsKey(GraphQLConstants.FrameErrorConstants.ERROR_CODE));
        Assert.assertEquals(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE),
                "Invalid operation payload");
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.BAD_REQUEST));

        msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null,\"query\":\"mutation {\\n  "
                + "changeLiftStatusChange {\\n    id\\n    name\\n    }\\n}\\n\"}}";
        msg = new TextWebSocketFrame(msgText);
        inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BAD_REQUEST);
        inboundProcessorResponseDTO.setErrorMessage("Invalid operation. Only allowed Subscription type operations");
        inboundProcessorResponseDTO.setId("1");
        PowerMockito.when(GraphQLProcessor.getBadRequestGraphQLFrameErrorDTO(
                        "Invalid operation. Only allowed Subscription type operations", "1"))
                .thenReturn(inboundProcessorResponseDTO);
        processorResponseDTO = graphQLRequestProcessor.handleRequest(msg, channelHandlerContext, inboundMessageContext,
                usageDataPublisher);
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(),
                "Invalid operation. Only allowed Subscription type operations");
        Assert.assertEquals(processorResponseDTO.getErrorCode(), GraphQLConstants.FrameErrorConstants.BAD_REQUEST);
        Assert.assertNotNull(processorResponseDTO.getErrorResponseString());
        errorJson = (JSONObject) jsonParser.parse(processorResponseDTO.getErrorResponseString());
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        payload = (JSONObject) errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertEquals(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE),
                "Invalid operation. Only allowed Subscription type operations");
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.BAD_REQUEST));
    }

    @Test
    public void testHandleRequestInvalidQueryPayload() throws Exception {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        int msgSize = 100;
        String msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null,\"query\":\"subscription {\\n  "
                + "liftStatusChange {\\n    id\\n    name\\n invalidField\\n }\\n}\\n\"}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);

        // Get schema and parse
        String graphqlDirPath = "graphQL" + File.separator;
        String relativePath = graphqlDirPath + "schema_with_additional_props.graphql";
        String schemaString = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath));
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry registry = schemaParser.parse(schemaString);
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        GraphQLSchemaDTO schemaDTO = new GraphQLSchemaDTO(schema, registry);
        PowerMockito.when(DataHolder.getInstance().getGraphQLSchemaDTOForAPI(
                inboundMessageContext.getElectedAPI().getUuid())).thenReturn(schemaDTO);

        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertTrue(processorResponseDTO.getErrorMessage()
                .contains(GraphQLConstants.FrameErrorConstants.GRAPHQL_INVALID_QUERY_MESSAGE));
        Assert.assertEquals(processorResponseDTO.getErrorCode(),
                GraphQLConstants.FrameErrorConstants.GRAPHQL_INVALID_QUERY);
        Assert.assertNotNull(processorResponseDTO.getErrorResponseString());
        JSONParser jsonParser = new JSONParser();
        JSONObject errorJson = (JSONObject) jsonParser.parse(processorResponseDTO.getErrorResponseString());
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        JSONObject payload = (JSONObject) errorJson.get(
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertTrue(((String) payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE)).contains(
                GraphQLConstants.FrameErrorConstants.GRAPHQL_INVALID_QUERY_MESSAGE));
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.GRAPHQL_INVALID_QUERY));
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
    }

    @Test
    public void testHandleRequestInvalidScope() throws Exception  {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null,\"query\":\"subscription {\\n  "
                + "liftStatusChange {\\n    id\\n    name\\n }\\n}\\n\"}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);

        // Get schema and parse
        String graphqlDirPath = "graphQL" + File.separator;
        String relativePath = graphqlDirPath + "schema_with_additional_props.graphql";
        String schemaString = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath));
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry registry = schemaParser.parse(schemaString);
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        GraphQLSchemaDTO schemaDTO = new GraphQLSchemaDTO(schema, registry);
        PowerMockito.when(DataHolder.getInstance().getGraphQLSchemaDTOForAPI(
                inboundMessageContext.getElectedAPI().getUuid())).thenReturn(schemaDTO);

        InboundProcessorResponseDTO graphQLProcessorResponseDTO = new InboundProcessorResponseDTO();
        graphQLProcessorResponseDTO.setError(true);
        graphQLProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR);
        graphQLProcessorResponseDTO.setErrorMessage("User is NOT authorized to access the Resource");
        graphQLProcessorResponseDTO.setCloseConnection(false);
        graphQLProcessorResponseDTO.setId("1");
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(graphQLProcessorResponseDTO);
        PowerMockito.when(GraphQLProcessorUtil.getOperationList(Mockito.anyObject(), Mockito.anyObject(),
                Mockito.anyString())).thenReturn("liftStatusChange");

        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertEquals(processorResponseDTO.getErrorMessage(), "User is NOT authorized to access the Resource");
        Assert.assertEquals(processorResponseDTO.getErrorCode(),
                GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR);
        Assert.assertNotNull(processorResponseDTO.getErrorResponseString());
        JSONParser jsonParser = new JSONParser();
        JSONObject errorJson = (JSONObject) jsonParser.parse(processorResponseDTO.getErrorResponseString());
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        JSONObject payload = (JSONObject) errorJson.get(
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertEquals(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE),
                "User is NOT authorized to access the Resource");
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.RESOURCE_FORBIDDEN_ERROR));
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
    }

    @Test
    public void testHandleRequestTooDeep() throws Exception {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null,\"query\":\"subscription {\\n  "
                + "liftStatusChange {\\n    id\\n    name\\n    }\\n}\\n\"}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);

        // Get schema and parse
        String graphqlDirPath = "graphQL" + File.separator;
        String relativePath = graphqlDirPath + "schema_with_additional_props.graphql";
        String schemaString = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath));
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry registry = schemaParser.parse(schemaString);
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        GraphQLSchemaDTO schemaDTO = new GraphQLSchemaDTO(schema, registry);
        PowerMockito.when(DataHolder.getInstance().getGraphQLSchemaDTOForAPI(
                inboundMessageContext.getElectedAPI().getUuid())).thenReturn(schemaDTO);
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(responseDTO);
        PowerMockito.when(GraphQLProcessorUtil.getOperationList(Mockito.anyObject(), Mockito.anyObject(),
                Mockito.anyString())).thenReturn("liftStatusChange");

        VerbInfoDTO verbInfoDTO = new VerbInfoDTO();
        verbInfoDTO.setHttpVerb("SUBSCRIPTION");
        verbInfoDTO.setThrottling("Unlimited");
        Set<VerbInfoDTO> verbInfoDTOS = new HashSet<>();
        verbInfoDTOS.add(verbInfoDTO);
        ResourceInfoDTO resourceInfoDTO = new ResourceInfoDTO();
        resourceInfoDTO.setHttpVerbs(verbInfoDTOS);
        resourceInfoDTO.setUrlPattern("liftStatusChange");
        Map<String, ResourceInfoDTO> resourcesMap = new HashMap<>();
        resourcesMap.put("liftStatusChange", resourceInfoDTO);
        inboundMessageContext.setResourcesMap(resourcesMap);
        APIKeyValidationInfoDTO infoDTO = new APIKeyValidationInfoDTO();
        infoDTO.setGraphQLMaxComplexity(4);
        infoDTO.setGraphQLMaxDepth(1);
        inboundMessageContext.setInfoDTO(infoDTO);
        PowerMockito.when(APIUtil.getResourceInfoDTOCacheKey(inboundMessageContext.getElectedAPI().getContext(),
                inboundMessageContext.getElectedAPI().getApiVersion(), "liftStatusChange",
                GraphQLConstants.SubscriptionConstants.HTTP_METHOD_NAME)).thenReturn("");

        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertTrue(processorResponseDTO.getErrorMessage().contains(
                GraphQLConstants.FrameErrorConstants.GRAPHQL_QUERY_TOO_DEEP_MESSAGE));
        Assert.assertEquals(processorResponseDTO.getErrorCode(),
                GraphQLConstants.FrameErrorConstants.GRAPHQL_QUERY_TOO_DEEP);
        Assert.assertNotNull(processorResponseDTO.getErrorResponseString());
        JSONParser jsonParser = new JSONParser();
        JSONObject errorJson = (JSONObject) jsonParser.parse(processorResponseDTO.getErrorResponseString());
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        JSONObject payload = (JSONObject) errorJson.get(
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertTrue(((String) payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE))
                .contains(GraphQLConstants.FrameErrorConstants.GRAPHQL_QUERY_TOO_DEEP_MESSAGE));
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.GRAPHQL_QUERY_TOO_DEEP));
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
    }

    @Test
    public void testHandleRequestThrottle() throws Exception {

        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        String msgText = "{\"id\":\"1\",\"type\":\"start\",\"payload\":{\"variables\":{},\"extensions\":{},"
                + "\"operationName\":null,\"query\":\"subscription {\\n  "
                + "liftStatusChange {\\n    id\\n    name\\n    }\\n}\\n\"}}";
        TextWebSocketFrame msg = new TextWebSocketFrame(msgText);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(GraphQLProcessor.authenticateGraphQLJWTToken(inboundMessageContext)).thenReturn(responseDTO);

        // Get schema and parse
        String graphqlDirPath = "graphQL" + File.separator;
        String relativePath = graphqlDirPath + "schema_with_additional_props.graphql";
        String schemaString = IOUtils.toString(getClass().getClassLoader().getResourceAsStream(relativePath));
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry registry = schemaParser.parse(schemaString);
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
        GraphQLSchemaDTO schemaDTO = new GraphQLSchemaDTO(schema, registry);
        PowerMockito.when(DataHolder.getInstance().getGraphQLSchemaDTOForAPI(
                inboundMessageContext.getElectedAPI().getUuid())).thenReturn(schemaDTO);
        PowerMockito.when(DataHolder.getInstance().getGraphQLSchemaDTOForAPI(
                inboundMessageContext.getElectedAPI().getUuid())).thenReturn(schemaDTO);
        PowerMockito.when(GraphQLProcessor.validateScopes(inboundMessageContext, "liftStatusChange", "1"))
                .thenReturn(responseDTO);
        PowerMockito.when(GraphQLProcessorUtil.getOperationList(Mockito.anyObject(), Mockito.anyObject(),
                Mockito.anyString())).thenReturn("liftStatusChange");

        VerbInfoDTO verbInfoDTO = new VerbInfoDTO();
        verbInfoDTO.setHttpVerb("SUBSCRIPTION");
        verbInfoDTO.setThrottling("Unlimited");
        Set<VerbInfoDTO> verbInfoDTOS = new HashSet<>();
        verbInfoDTOS.add(verbInfoDTO);
        ResourceInfoDTO resourceInfoDTO = new ResourceInfoDTO();
        resourceInfoDTO.setHttpVerbs(verbInfoDTOS);
        resourceInfoDTO.setUrlPattern("liftStatusChange");
        Map<String, ResourceInfoDTO> resourcesMap = new HashMap<>();
        resourcesMap.put("liftStatusChange", resourceInfoDTO);
        inboundMessageContext.setResourcesMap(resourcesMap);
        PowerMockito.when(APIUtil.getResourceInfoDTOCacheKey(inboundMessageContext.getElectedAPI().getContext(),
                inboundMessageContext.getElectedAPI().getApiVersion(), "liftStatusChange",
                GraphQLConstants.SubscriptionConstants.HTTP_METHOD_NAME)).thenReturn("");

        InboundProcessorResponseDTO throttleResponseDTO = new InboundProcessorResponseDTO();
        throttleResponseDTO.setError(true);
        throttleResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR);
        throttleResponseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE);
        throttleResponseDTO.setId("1");
        PowerMockito.when(GraphQLProcessor.doThrottleForGraphQL(msg, channelHandlerContext, verbInfoDTO,
                inboundMessageContext, "1", usageDataPublisher)).thenReturn(throttleResponseDTO);
        InboundProcessorResponseDTO processorResponseDTO = graphQLRequestProcessor.handleRequest(msg,
                channelHandlerContext, inboundMessageContext, usageDataPublisher);
        Assert.assertTrue(processorResponseDTO.isError());
        Assert.assertTrue(processorResponseDTO.getErrorMessage().contains(
                GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE));
        Assert.assertEquals(processorResponseDTO.getErrorCode(),
                GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR);
        Assert.assertNotNull(processorResponseDTO.getErrorResponseString());
        JSONParser jsonParser = new JSONParser();
        JSONObject errorJson = (JSONObject) jsonParser.parse(processorResponseDTO.getErrorResponseString());
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE),
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        Assert.assertEquals(errorJson.get(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID), "1");
        JSONObject payload = (JSONObject) errorJson.get(
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD);
        Assert.assertTrue(((String) payload.get(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE))
                .contains(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR_MESSAGE));
        Assert.assertEquals(String.valueOf(payload.get(GraphQLConstants.FrameErrorConstants.ERROR_CODE)),
                String.valueOf(GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR));
        Assert.assertFalse(processorResponseDTO.isCloseConnection());
    }

    private InboundMessageContext createApiMessageContext(API api) {
        InboundMessageContext inboundMessageContext = new InboundMessageContext();
        inboundMessageContext.setTenantDomain("carbon.super");
        inboundMessageContext.setElectedAPI(api);
        inboundMessageContext.setJWTToken(true);
        return inboundMessageContext;
    }
}
