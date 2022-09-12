/*
 *   Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.apimgt.gateway.handlers;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.InboundMessageContextDataHolder;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLProcessor;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLProcessorUtil;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLRequestProcessor;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityUtils;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.jwt.JWTValidator;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.gateway.throttling.publisher.ThrottleDataPublisher;
import org.wso2.carbon.apimgt.gateway.utils.APIMgtGoogleAnalyticsUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.jwt.JWTValidationService;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.apimgt.usage.publisher.DataPublisherUtil;
import org.wso2.carbon.apimgt.usage.publisher.internal.UsageComponent;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.databridge.agent.DataPublisher;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.cache.Cache;
import javax.cache.CacheBuilder;
import javax.cache.CacheConfiguration;
import javax.cache.CacheManager;
import javax.cache.Caching;

/**
 * Test class for WebsocketInboundHandler
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({PrivilegedCarbonContext.class, WebsocketInboundHandler.class, MultitenantUtils.class, DataPublisherUtil.class,
        UsageComponent.class,  ServiceReferenceHolder.class, Caching.class,
        APISecurityUtils.class, WebsocketUtil.class, ThrottleDataPublisher.class, APIUtil.class, RegistryService.class,
        org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder.class, GraphQLProcessorUtil.class,
        GraphQLProcessor.class, APIMgtUsageDataPublisher.class})
@PowerMockIgnore("javax.net.ssl.SSLContext")
public class WebsocketInboundHandlerTestCase {

    private static final String channelIdString = "11111";
    private static final String remoteIP = "192.168.0.100";
    private static final String APPLICATION_TIER = "ApplicationTier";
    private static final String APPLICATION_NAME = "ApplicationName";
    private static final String APPLICATION_ID = "1";
    private static final String TIER = "Tier";
    private static final String SUBSCRIBER = "subscriber";
    private static final String APPLICATION_CONSUMER_KEY = "NdYZFnAfUa7uST1giZrmIq8he8Ya";
    private String SUPER_TENANT_DOMAIN = "carbon.super";
    private ChannelHandlerContext channelHandlerContext;
    private API websocketAPI;
    private API graphQLAPI;
    private HttpHeaders headers;
    private String AUTHORIZATION = "Bearer eyJ4NXQiOiJNell4TW1Ga09HWXdNV0kwWldObU5EY3hOR1l3WW1NNFpUQTNNV"
            + "0kyTkRBelpHUXpOR00wWkdSbE5qSmtPREZrWkRSaU9URmtNV0ZoTXpVMlpHVmxOZyIsImtpZCI6Ik16WXhNbUZrT0dZd01XSTBaV05tT"
            + "kRjeE5HWXdZbU00WlRBM01XSTJOREF6WkdRek5HTTBaR1JsTmpKa09ERmtaRFJpT1RGa01XRmhNelUyWkdWbE5nX1JTMjU2IiwiYWxnI"
            + "joiUlMyNTYifQ.eyJzdWIiOiJhZG1pbiIsImF1dCI6IkFQUExJQ0FUSU9OIiwiYXVkIjoib0xPb0lEeGxMbUtYb3BxVkk0NkFlQ1o5OE"
            + "xZYSIsIm5iZiI6MTYzNzY0NjE5MiwiYXpwIjoib0xPb0lEeGxMbUtYb3BxVkk0NkFlQ1o5OExZYSIsInNjb3BlIjoiZGVmYXVsdCIsIm"
            + "lzcyI6Imh0dHBzOlwvXC9sb2NhbGhvc3Q6OTQ0M1wvb2F1dGgyXC90b2tlbiIsImV4cCI6MTYzNzY0OTc5MiwiaWF0IjoxNjM3NjQ2MT"
            + "kyLCJqdGkiOiIwYjc0MTVhOS04OTcxLTQyMjItOWFlNC0xMGM1ZDIzNTBhOGIifQ.Hu0P_wlE_yQOH6bOgiBPHF5Qz6b2a2uNU2Gjq6F"
            + "fFpjrbqfzFWW-PDqXQ6T5Vx8waySvH4DtzObZu-XI_R0xYgd0zt0R1wUunHW7ZV6sQZRdDPUZETci984B7AAlODm3CW8zdlWey2ldiqd"
            + "GqAXQHIW_iv0C1udag7r5ycMhZr-nP9iJ66pC_KjN__GMYKWjD05dGNsVz4yEvzfqZ_le7_e_VzQ3PHSqiVwhK0FeX_RM9wax1kJASXF"
            + "a2PCF-Pxen60NAuQfjzjToXE98ayAKdKFV_1D1DNc9kOmZGdc8Lp1Dw6HqhSoLeesuLVNamoqrNEJ0auUqx4VrB16Q66T6A";
    private String UPGRADE = "websocket";
    private String API_KEY = "587hfbt4i8ydno87ywq";
    private String CACHE_KEY = "587hfbt4i8ydno87ywq:https://localhost/1.0";
    private String TOKEN_CACHE_EXPIRY = "900";
    private WebsocketInboundHandler websocketInboundHandler;
    private APIManagerConfiguration apiManagerConfiguration;
    private AxisConfiguration axisConfiguration;
    private ConfigurationContext configurationContext;
    private Cache gatewayCache;
    ServiceReferenceHolder serviceReferenceHolder;
    private GraphQLRequestProcessor graphQLRequestProcessor;
    private APIMgtUsageDataPublisher usageDataPublisher;
    private FullHttpRequest fullHttpRequest;

    @Before
    public void setup() throws Exception {
        System.setProperty("carbon.home", "test");
        channelHandlerContext = Mockito.mock(ChannelHandlerContext.class);
        fullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "ws://localhost:8080/graphql");
        Channel channel = Mockito.mock(Channel.class);
        Attribute attribute = Mockito.mock(Attribute.class);
        ChannelId channelId = Mockito.mock(ChannelId.class);
        Mockito.when(channel.attr(AttributeKey.valueOf("API_PROPERTIES"))).thenReturn(attribute);
        Mockito.when(attribute.get()).thenReturn(00000000L);
        Mockito.when(channel.attr(AttributeKey.valueOf("api.ut.requestTime"))).thenReturn(attribute);
        Mockito.when(channelHandlerContext.channel()).thenReturn(channel);
        Mockito.when(channel.id()).thenReturn(channelId);
        Mockito.when(channelId.asLongText()).thenReturn(channelIdString);
        SocketAddress socketAddress = Mockito.mock(SocketAddress.class);
        Mockito.when(channel.remoteAddress()).thenReturn(socketAddress);
        PowerMockito.mockStatic(APIMgtUsageDataPublisher.class);
        usageDataPublisher = Mockito.mock(APIMgtUsageDataPublisher.class);
        headers = Mockito.mock(HttpHeaders.class);
        websocketAPI = new API(UUID.randomUUID().toString(), 1, "admin", "WSAPI", "1.0.0", "/wscontext", "Unlimited",
                "WS", false);
        graphQLAPI = new API(UUID.randomUUID().toString(), 2, "admin", "GraphQLAPI", "1.0.0", "/graphql", "Unlimited",
                APIConstants.GRAPHQL_API, false);
        PowerMockito.mockStatic(MultitenantUtils.class);
        PowerMockito.mockStatic(DataPublisherUtil.class);
        PowerMockito.mockStatic(UsageComponent.class);
        PowerMockito.mockStatic(PrivilegedCarbonContext.class);
        PrivilegedCarbonContext privilegedCarbonContext = Mockito.mock(PrivilegedCarbonContext.class);
        PowerMockito.when(PrivilegedCarbonContext.getThreadLocalCarbonContext()).thenReturn(privilegedCarbonContext);
        PowerMockito.mockStatic(ServiceReferenceHolder.class);
        PowerMockito.mockStatic(Caching.class);
        serviceReferenceHolder = Mockito.mock(ServiceReferenceHolder.class);
        apiManagerConfiguration = Mockito.mock(APIManagerConfiguration.class);
        configurationContext = Mockito.mock(ConfigurationContext.class);
        axisConfiguration = Mockito.mock(AxisConfiguration.class);
        PowerMockito.when(ServiceReferenceHolder.getInstance()).thenReturn(serviceReferenceHolder);
        PowerMockito.when(serviceReferenceHolder.getAPIManagerConfiguration()).thenReturn(apiManagerConfiguration);
        PowerMockito.when(serviceReferenceHolder.getServerConfigurationContext()).thenReturn(configurationContext);
        PowerMockito.when(configurationContext.getAxisConfiguration()).thenReturn(axisConfiguration);
        PowerMockito.mockStatic(APIUtil.class);
        PowerMockito.mockStatic(WebsocketUtil.class);
        PowerMockito.when(WebsocketUtil.getRemoteIP(channelHandlerContext)).thenReturn(remoteIP);
        JWTValidationService jwtValidationService = Mockito.mock(JWTValidationService.class);
        PowerMockito.when(serviceReferenceHolder.getJwtValidationService()).thenReturn(jwtValidationService);
        PowerMockito.when(serviceReferenceHolder.getJwtValidationService()
                .getKeyManagerNameIfJwtValidatorExist(Mockito.anyObject())).thenReturn("Resident Key Manager");
        PowerMockito.when(DataPublisherUtil.getHostAddress()).thenReturn(remoteIP);
        gatewayCache = Mockito.mock(Cache.class);
        CacheManager cacheManager = Mockito.mock(CacheManager.class);
        CacheBuilder cacheBuilder = Mockito.mock(CacheBuilder.class);
        PowerMockito.when(Caching.getCacheManager(APIConstants.API_MANAGER_CACHE_MANAGER)).thenReturn(cacheManager);
        PowerMockito.when(cacheManager.createCacheBuilder(APIConstants.GATEWAY_KEY_CACHE_NAME)).thenReturn(cacheBuilder);
        Mockito.when(apiManagerConfiguration.getFirstProperty(APIConstants.TOKEN_CACHE_EXPIRY)).thenReturn("900");
        CacheConfiguration.Duration duration = new CacheConfiguration.Duration(TimeUnit.SECONDS,
                Long.parseLong(TOKEN_CACHE_EXPIRY));
        Mockito.when(gatewayCache.get(API_KEY)).thenReturn("fhgvjhhhjkghj");
        Mockito.when(gatewayCache.get(CACHE_KEY)).thenReturn(null);
        Mockito.when(cacheManager.getCache(APIConstants.GATEWAY_TOKEN_CACHE_NAME)).thenReturn(gatewayCache);
        Mockito.when(cacheBuilder.setExpiry(CacheConfiguration.ExpiryType.MODIFIED, duration)).thenReturn(cacheBuilder);
        Mockito.when(cacheBuilder.setExpiry(CacheConfiguration.ExpiryType.ACCESSED, duration)).thenReturn(cacheBuilder);
        Mockito.when(cacheBuilder.setStoreByValue(false)).thenReturn(cacheBuilder);
        Mockito.when(cacheBuilder.build()).thenReturn(gatewayCache);
        PowerMockito.mockStatic(GraphQLProcessorUtil.class);
        graphQLRequestProcessor = Mockito.mock(GraphQLRequestProcessor.class);
        PowerMockito.whenNew(GraphQLRequestProcessor.class).withAnyArguments().thenReturn(graphQLRequestProcessor);

        APIMgtGoogleAnalyticsUtils apiMgtGoogleAnalyticsUtils = Mockito.mock(APIMgtGoogleAnalyticsUtils.class);
        Mockito.doNothing().when(apiMgtGoogleAnalyticsUtils).init("");
        PowerMockito.whenNew(APIMgtGoogleAnalyticsUtils.class).withAnyArguments().thenReturn(apiMgtGoogleAnalyticsUtils);
        websocketInboundHandler = new WebsocketInboundHandler();
    }

    @Test
    public void testWSCloseFrameResponse() throws Exception {
        Object msg = "msg";
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString))); // No error has occurred context exists in data-holder map.

        CloseWebSocketFrame closeWebSocketFrame = Mockito.mock(CloseWebSocketFrame.class);
        websocketInboundHandler.channelRead(channelHandlerContext, closeWebSocketFrame);
        Assert.assertFalse((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString))); // Closing connection. Remove context from data-holder map.
    }

    @Test
    public void testWSHandshakeResponse() throws Exception {

        // For Web Socket APIs
        APIKeyValidationInfoDTO infoDTO = createAPIKeyValidationInfo(websocketAPI);
        InboundMessageContext inboundMessageContext = createApiMessageContext(websocketAPI);
        InboundMessageContextDataHolder.getInstance()
                .addInboundMessageContextForConnection(channelIdString, inboundMessageContext);
        inboundMessageContext.setInfoDTO(infoDTO);
        inboundMessageContext.setJWTToken(true);
        ChannelPipeline channelPipeline = Mockito.mock(ChannelPipeline.class);
        Mockito.when(channelHandlerContext.channel().pipeline()).thenReturn(channelPipeline);
        Mockito.when(channelPipeline.get("ssl")).thenReturn(null);
        fullHttpRequest.headers().set(org.apache.http.HttpHeaders.AUTHORIZATION, AUTHORIZATION);
        fullHttpRequest.headers().set(org.apache.http.HttpHeaders.UPGRADE, UPGRADE);
        HashMap<String, Object> apiProperties = new HashMap<>();
        PowerMockito.whenNew(HashMap.class).withAnyArguments().thenReturn(apiProperties);
        PowerMockito.when(APIUtil.getHostAddress()).thenReturn("localhost");
        PowerMockito.when(WebsocketUtil.getApi(fullHttpRequest.uri(), SUPER_TENANT_DOMAIN)).thenReturn(websocketAPI);
        JWTValidator jwtValidator = Mockito.mock(JWTValidator.class);
        PowerMockito.whenNew(JWTValidator.class).withAnyArguments().thenReturn(jwtValidator);
        AuthenticationContext authenticationContext = Mockito.mock(AuthenticationContext.class);
        Mockito.when(jwtValidator.authenticateForWSAndGraphQL(inboundMessageContext.getSignedJWTInfo(),
                        inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion()))
                .thenReturn(authenticationContext);
        PowerMockito.when(WebsocketUtil.validateAuthenticationContext(inboundMessageContext, false)).thenReturn(true);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(responseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, fullHttpRequest);
        validateApiProperties(apiProperties, infoDTO, inboundMessageContext);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));// No error has occurred context exists in data-holder map
        Assert.assertEquals(inboundMessageContext.getHeaders().get(org.apache.http.HttpHeaders.AUTHORIZATION),
                AUTHORIZATION);
        Assert.assertEquals(inboundMessageContext.getToken(),
                fullHttpRequest.headers().get(APIMgtGatewayConstants.WS_JWT_TOKEN_HEADER));
        Assert.assertEquals(inboundMessageContext.getUserIP(), remoteIP);

        // error response when the deny policies are enabled
        InboundProcessorResponseDTO blockedAPIResponseDTO = new InboundProcessorResponseDTO();
        blockedAPIResponseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST_MESSAGE);
        blockedAPIResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST);
        blockedAPIResponseDTO.setError(true);
        blockedAPIResponseDTO.setCloseConnection(true);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(blockedAPIResponseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, fullHttpRequest);
        Assert.assertFalse((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().containsKey(
                channelIdString)));//Error should occur due to the context does not exist in data-holder map
    }

    @Test
    public void testWSFrameResponse() throws Exception {
        InboundMessageContext inboundMessageContext = createApiMessageContext(websocketAPI);
        InboundMessageContextDataHolder.getInstance()
                .addInboundMessageContextForConnection(channelIdString, inboundMessageContext);
        ByteBuf content = Mockito.mock(ByteBuf.class);
        WebSocketFrame msg = Mockito.mock(WebSocketFrame.class);
        Mockito.when(msg.content()).thenReturn(content);

        PowerMockito.mockStatic(ThrottleDataPublisher.class);
        ThrottleDataPublisher throttleDataPublisher = Mockito.mock(ThrottleDataPublisher.class);
        Mockito.when(ServiceReferenceHolder.getInstance().getThrottleDataPublisher()).thenReturn(throttleDataPublisher);
        DataPublisher dataPublisher = Mockito.mock(DataPublisher.class);
        Mockito.when(ThrottleDataPublisher.getDataPublisher()).thenReturn(dataPublisher);
        Mockito.when(dataPublisher.tryPublish(Mockito.anyObject())).thenReturn(true);
        WebSocketThrottleResponseDTO webSocketThrottleResponseDTO = new WebSocketThrottleResponseDTO();
        webSocketThrottleResponseDTO.setThrottled(false);
        Mockito.when(WebsocketUtil.doThrottle(channelHandlerContext, msg, null, inboundMessageContext))
                .thenReturn(webSocketThrottleResponseDTO);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        Mockito.when(graphQLRequestProcessor.handleRequest(msg, channelHandlerContext, inboundMessageContext,
                null)).thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(responseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));// No error has occurred context exists in data-holder map.

        webSocketThrottleResponseDTO.setThrottled(true);
        // error response (the connection will not be closed for Web socket APIs)
        Mockito.when(WebsocketUtil.doThrottle(channelHandlerContext, msg, null, inboundMessageContext))
                .thenReturn(webSocketThrottleResponseDTO);
        responseDTO.setError(true);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));
    }

    @Test
    public void testGraphQLHandshakeResponse() throws Exception {

        // For GraphQL APIs
        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        InboundMessageContextDataHolder.getInstance()
                .addInboundMessageContextForConnection(channelIdString, inboundMessageContext);
        fullHttpRequest.headers().set(org.apache.http.HttpHeaders.AUTHORIZATION, AUTHORIZATION);
        fullHttpRequest.headers().set(org.apache.http.HttpHeaders.UPGRADE, UPGRADE);
        PowerMockito.when(WebsocketUtil.getApi(fullHttpRequest.uri(), SUPER_TENANT_DOMAIN)).thenReturn(graphQLAPI);
        JWTValidator jwtValidator = Mockito.mock(JWTValidator.class);
        PowerMockito.whenNew(JWTValidator.class).withAnyArguments().thenReturn(jwtValidator);
        AuthenticationContext authenticationContext = Mockito.mock(AuthenticationContext.class);
        Mockito.when(jwtValidator.authenticateForWSAndGraphQL(inboundMessageContext.getSignedJWTInfo(),
                        inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion()))
                .thenReturn(authenticationContext);
        PowerMockito.when(WebsocketUtil.validateAuthenticationContext(inboundMessageContext, false)).thenReturn(true);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(responseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, fullHttpRequest);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));// No error has occurred context exists in data-holder map.
        Assert.assertEquals(inboundMessageContext.getHeaders().get(org.apache.http.HttpHeaders.AUTHORIZATION),
                AUTHORIZATION);
        Assert.assertEquals(inboundMessageContext.getToken(),
                fullHttpRequest.headers().get(APIMgtGatewayConstants.WS_JWT_TOKEN_HEADER));
        Assert.assertEquals(inboundMessageContext.getUserIP(), remoteIP);

        // error response
        PowerMockito.when(WebsocketUtil.validateAuthenticationContext(inboundMessageContext, false)).thenReturn(false);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(responseDTO);
        responseDTO.setError(true);
        websocketInboundHandler.channelRead(channelHandlerContext, fullHttpRequest);
        Assert.assertFalse(InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString));  // Closing connection error has occurred
    }

    @Test
    public void testGraphQLFrameResponse() throws Exception {
        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        InboundMessageContextDataHolder.getInstance().addInboundMessageContextForConnection(channelIdString,
                inboundMessageContext);
        ByteBuf content = Mockito.mock(ByteBuf.class);
        TextWebSocketFrame msg = Mockito.mock(TextWebSocketFrame.class);
        Mockito.when(msg.content()).thenReturn(content);
        PowerMockito.mockStatic(ThrottleDataPublisher.class);
        ThrottleDataPublisher throttleDataPublisher = Mockito.mock(ThrottleDataPublisher.class);
        Mockito.when(ServiceReferenceHolder.getInstance().getThrottleDataPublisher())
                .thenReturn(throttleDataPublisher);
        DataPublisher dataPublisher = Mockito.mock(DataPublisher.class);
        Mockito.when(ThrottleDataPublisher.getDataPublisher()).thenReturn(dataPublisher);
        Mockito.when(dataPublisher.tryPublish(Mockito.anyObject())).thenReturn(true);
        PowerMockito.mockStatic(GraphQLProcessor.class);
        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        Mockito.when(graphQLRequestProcessor.handleRequest(msg, channelHandlerContext, inboundMessageContext,
                null)).thenReturn(inboundProcessorResponseDTO);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(),
                Mockito.any())).thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));// No error has occurred context exists in data-holder map.

        // error response (connection is not closing scenario)
        inboundProcessorResponseDTO.setError(true);
        inboundProcessorResponseDTO.setCloseConnection(false);
        inboundProcessorResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.INTERNAL_SERVER_ERROR);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertTrue((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));
        // error response (connection is closing scenario)
        inboundProcessorResponseDTO.setCloseConnection(true);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertFalse((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap()
                .containsKey(channelIdString)));
    }

    @Test
    public void testWSBlockedResponse() throws Exception {
        InboundMessageContext inboundMessageContext = createApiMessageContext(websocketAPI);
        InboundMessageContextDataHolder.getInstance()
                .addInboundMessageContextForConnection(channelIdString, inboundMessageContext);
        ByteBuf content = Mockito.mock(ByteBuf.class);
        WebSocketFrame msg = Mockito.mock(WebSocketFrame.class);
        Mockito.when(msg.content()).thenReturn(content);

        PowerMockito.mockStatic(ThrottleDataPublisher.class);
        ThrottleDataPublisher throttleDataPublisher = Mockito.mock(ThrottleDataPublisher.class);
        Mockito.when(ServiceReferenceHolder.getInstance().getThrottleDataPublisher()).thenReturn(throttleDataPublisher);
        DataPublisher dataPublisher = Mockito.mock(DataPublisher.class);
        Mockito.when(ThrottleDataPublisher.getDataPublisher()).thenReturn(dataPublisher);
        Mockito.when(dataPublisher.tryPublish(Mockito.anyObject())).thenReturn(true);
        WebSocketThrottleResponseDTO webSocketThrottleResponseDTO = new WebSocketThrottleResponseDTO();
        webSocketThrottleResponseDTO.setThrottled(false);
        Mockito.when(WebsocketUtil.doThrottle(channelHandlerContext, msg, null, inboundMessageContext))
                .thenReturn(webSocketThrottleResponseDTO);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        Mockito.when(graphQLRequestProcessor.handleRequest(msg, channelHandlerContext, inboundMessageContext, null))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        InboundProcessorResponseDTO blockedAPIResponseDTO = new InboundProcessorResponseDTO();
        blockedAPIResponseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST_MESSAGE);
        blockedAPIResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST);
        blockedAPIResponseDTO.setError(true);
        blockedAPIResponseDTO.setCloseConnection(true);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(blockedAPIResponseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertFalse((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().containsKey(
                channelIdString)));//Error should occur due to the context does not exist in data-holder map
    }

    @Test
    public void exceptionCaughtTest() throws Exception {
        Throwable cause = new CorruptedWebSocketFrameException(WebSocketCloseStatus.MESSAGE_TOO_BIG,
                "Max frame length of 65536 has been exceeded.");
        Attribute<Object> attributes = Mockito.mock(Attribute.class);
        Mockito.when(channelHandlerContext.channel().attr(AttributeKey.valueOf("API_PROPERTIES")))
                .thenReturn(attributes);
        HashMap apiProperties = new HashMap();
        Mockito.when((HashMap)attributes.get()).thenReturn(apiProperties);
        websocketInboundHandler.exceptionCaught(channelHandlerContext, cause);
        Assert.assertEquals(apiProperties.get("api.ut.WS_SC"), 1009);
    }

    @Test
    public void testBlockedGraphQLAPIHandshakeResponse() throws Exception {

        // For GraphQL APIs
        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        InboundMessageContextDataHolder.getInstance()
                .addInboundMessageContextForConnection(channelIdString, inboundMessageContext);
        fullHttpRequest.headers().set(org.apache.http.HttpHeaders.AUTHORIZATION, AUTHORIZATION);
        fullHttpRequest.headers().set(org.apache.http.HttpHeaders.UPGRADE, UPGRADE);
        PowerMockito.when(WebsocketUtil.getApi(fullHttpRequest.uri(), SUPER_TENANT_DOMAIN)).thenReturn(graphQLAPI);
        JWTValidator jwtValidator = Mockito.mock(JWTValidator.class);
        PowerMockito.whenNew(JWTValidator.class).withAnyArguments().thenReturn(jwtValidator);
        AuthenticationContext authenticationContext = Mockito.mock(AuthenticationContext.class);
        Mockito.when(jwtValidator.authenticateForWSAndGraphQL(inboundMessageContext.getSignedJWTInfo(),
                inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion()))
                .thenReturn(authenticationContext);
        PowerMockito.when(WebsocketUtil.validateAuthenticationContext(inboundMessageContext, false)).thenReturn(true);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        InboundProcessorResponseDTO blockedAPIResponseDTO = new InboundProcessorResponseDTO();
        blockedAPIResponseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST_MESSAGE);
        blockedAPIResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST);
        blockedAPIResponseDTO.setError(true);
        blockedAPIResponseDTO.setCloseConnection(true);
        PowerMockito.when(WebsocketUtil.validateDenyPolicies(Mockito.anyObject(), Mockito.anyObject()))
                .thenReturn(blockedAPIResponseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, fullHttpRequest);
        Assert.assertFalse((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().containsKey(
                channelIdString)));//Error should occur due to the context does not exist in data-holder map
    }

    @Test
    public void testBlockedGraphQLAPIFrameResponse() throws Exception {
        InboundMessageContext inboundMessageContext = createApiMessageContext(graphQLAPI);
        InboundMessageContextDataHolder.getInstance()
                .addInboundMessageContextForConnection(channelIdString, inboundMessageContext);
        ByteBuf content = Mockito.mock(ByteBuf.class);
        TextWebSocketFrame msg = Mockito.mock(TextWebSocketFrame.class);
        Mockito.when(msg.content()).thenReturn(content);
        PowerMockito.mockStatic(ThrottleDataPublisher.class);
        ThrottleDataPublisher throttleDataPublisher = Mockito.mock(ThrottleDataPublisher.class);
        Mockito.when(ServiceReferenceHolder.getInstance().getThrottleDataPublisher()).thenReturn(throttleDataPublisher);
        DataPublisher dataPublisher = Mockito.mock(DataPublisher.class);
        Mockito.when(ThrottleDataPublisher.getDataPublisher()).thenReturn(dataPublisher);
        Mockito.when(dataPublisher.tryPublish(Mockito.anyObject())).thenReturn(true);
        PowerMockito.mockStatic(GraphQLProcessor.class);
        InboundProcessorResponseDTO inboundProcessorResponseDTO = new InboundProcessorResponseDTO();
        Mockito.when(graphQLRequestProcessor.handleRequest(msg, channelHandlerContext, inboundMessageContext, null))
                .thenReturn(inboundProcessorResponseDTO);
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        PowerMockito.when(WebsocketUtil.authenticateOAuthToken(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(responseDTO);
        PowerMockito.when(WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext))
                .thenReturn(responseDTO);
        InboundProcessorResponseDTO blockedAPIResponseDTO = new InboundProcessorResponseDTO();
        blockedAPIResponseDTO.setErrorMessage(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST_MESSAGE);
        blockedAPIResponseDTO.setErrorCode(GraphQLConstants.FrameErrorConstants.BLOCKED_REQUEST);
        blockedAPIResponseDTO.setError(true);
        blockedAPIResponseDTO.setCloseConnection(true);
        PowerMockito
                .when(graphQLRequestProcessor.handleRequest(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(blockedAPIResponseDTO);
        websocketInboundHandler.channelRead(channelHandlerContext, msg);
        Assert.assertFalse((InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().containsKey(
                channelIdString)));//Error should occur due to the context does not exist in data-holder map
    }

    private void validateApiProperties(HashMap apiPropertiesMap, APIKeyValidationInfoDTO infoDTO, InboundMessageContext inboundMessageContext) {
        API electedAPI = inboundMessageContext.getElectedAPI();
        Assert.assertEquals(electedAPI.getApiName(), apiPropertiesMap.get(APIMgtGatewayConstants.API));
        Assert.assertEquals(electedAPI.getApiVersion(), apiPropertiesMap.get(APIMgtGatewayConstants.VERSION));
        Assert.assertEquals(electedAPI.getApiName() + ":v" + electedAPI.getApiVersion(),
                apiPropertiesMap.get(APIMgtGatewayConstants.API_VERSION));
        Assert.assertEquals(inboundMessageContext.getApiContextUri(),
                apiPropertiesMap.get(APIMgtGatewayConstants.CONTEXT));
        Assert.assertEquals(String.valueOf(APIConstants.ApiTypes.API),
                apiPropertiesMap.get(APIMgtGatewayConstants.API_TYPE));
        Assert.assertEquals(APIUtil.getHostAddress(), apiPropertiesMap.get(APIMgtGatewayConstants.HOST_NAME));
        Assert.assertEquals(infoDTO.getConsumerKey(), apiPropertiesMap.get(APIMgtGatewayConstants.CONSUMER_KEY));
        Assert.assertEquals(infoDTO.getEndUserName(), apiPropertiesMap.get(APIMgtGatewayConstants.USER_ID));
        Assert.assertEquals(infoDTO.getApiPublisher(), apiPropertiesMap.get(APIMgtGatewayConstants.API_PUBLISHER));
        Assert.assertEquals(infoDTO.getEndUserName(), apiPropertiesMap.get(APIMgtGatewayConstants.END_USER_NAME));
        Assert.assertEquals(infoDTO.getApplicationName(),
                apiPropertiesMap.get(APIMgtGatewayConstants.APPLICATION_NAME));
        Assert.assertEquals(infoDTO.getApplicationId(), apiPropertiesMap.get(APIMgtGatewayConstants.APPLICATION_ID));
    }

    private InboundMessageContext createApiMessageContext(API api) {
        InboundMessageContext inboundMessageContext = new InboundMessageContext();
        inboundMessageContext.setTenantDomain(SUPER_TENANT_DOMAIN);
        inboundMessageContext.setElectedAPI(api);
        inboundMessageContext.setToken("test-backend-jwt-token");
        return inboundMessageContext;
    }

    private APIKeyValidationInfoDTO createAPIKeyValidationInfo(API api) {
        APIKeyValidationInfoDTO info = new APIKeyValidationInfoDTO();
        info.setAuthorized(true);
        info.setApplicationTier(APPLICATION_TIER);
        info.setTier(TIER);
        info.setSubscriberTenantDomain(SUPER_TENANT_DOMAIN);
        info.setSubscriber(SUBSCRIBER);
        info.setStopOnQuotaReach(true);
        info.setApiName(api.getApiName());
        info.setApplicationId(APPLICATION_ID);
        info.setType("PRODUCTION");
        info.setApiPublisher(api.getApiProvider());
        info.setApplicationName(APPLICATION_NAME);
        info.setConsumerKey(APPLICATION_CONSUMER_KEY);
        info.setEndUserName(SUBSCRIBER + "@" + SUPER_TENANT_DOMAIN);
        info.setApiTier(api.getApiTier());
        info.setEndUserToken("callerToken");
        info.setGraphQLMaxDepth(5);
        info.setGraphQLMaxComplexity(5);
        return info;
    }
}
