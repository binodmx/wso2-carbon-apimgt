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

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPFactory;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.axis2.description.TransportOutDescription;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.MessageContextCreatorForAxis2;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.CORSConfiguration;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.InboundMessageContextDataHolder;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLRequestProcessor;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.gateway.utils.APIMgtGoogleAnalyticsUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerAnalyticsConfiguration;
import org.wso2.carbon.apimgt.impl.caching.CacheProvider;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.JWTConfigurationDto;
import org.wso2.carbon.apimgt.impl.dto.ResourceInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.impl.jwt.SignedJWTInfo;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.SubscriptionDataHolder;
import org.wso2.carbon.apimgt.keymgt.model.SubscriptionDataStore;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.apimgt.usage.publisher.DataPublisherUtil;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.ganalytics.publisher.GoogleAnalyticsData;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.cache.Cache;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is a handler which is actually embedded to the netty pipeline which does operations such as
 * authentication and throttling for the websocket handshake and subsequent websocket frames.
 */
public class WebsocketInboundHandler extends ChannelInboundHandlerAdapter {
    private static final Log log = LogFactory.getLog(WebsocketInboundHandler.class);
    private static APIMgtUsageDataPublisher usageDataPublisher;
    private GraphQLRequestProcessor graphQLRequestProcessor = new GraphQLRequestProcessor();
    private final String API_PROPERTIES = "API_PROPERTIES";
    private final String WEB_SC_API_UT = "api.ut.WS_SC";

    public WebsocketInboundHandler() {
        initializeDataPublisher();
    }

    private void initializeDataPublisher() {
        if (APIUtil.isAnalyticsEnabled() && usageDataPublisher == null) {
            String publisherClass = getApiManagerAnalyticsConfiguration().getPublisherClass();

            try {
                synchronized (this) {
                    if (usageDataPublisher == null) {
                        try {
                            log.debug("Instantiating Web Socket Data Publisher");
                            usageDataPublisher = (APIMgtUsageDataPublisher) APIUtil.getClassForName(publisherClass)
                                    .newInstance();
                            usageDataPublisher.init();
                        } catch (ClassNotFoundException e) {
                            log.error("Class not found " + publisherClass, e);
                        } catch (InstantiationException e) {
                            log.error("Error instantiating " + publisherClass, e);
                        } catch (IllegalAccessException e) {
                            log.error("Illegal access to " + publisherClass, e);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Cannot publish event. " + e.getMessage(), e);
            }
        }
    }

    /**
     * extract the version from the request uri
     *
     * @param url
     * @return version String
     */
    private String getVersionFromUrl(final String url) {
        return url.replaceFirst(".*/([^/?]+).*", "$1");
    }

    //method removed because url is going to be always null
/*    private String getContextFromUrl(String url) {
        int lastIndex = 0;
        if (url != null) {
            lastIndex = url.lastIndexOf('/');
            return url.substring(0, lastIndex);
        } else {
            return "";
        }
    }*/

    @SuppressWarnings("unchecked")
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {

        ctx.channel().attr(AttributeKey.valueOf(APIMgtGatewayConstants.REQUEST_START_TIME)).set(System
                .currentTimeMillis());
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
        inboundMessageContext.setUserIP(WebsocketUtil.getRemoteIP(ctx));

        //check if the request is a handshake
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;

            // This block is for the health check of the ports 8099 and 9099
            if (req.headers() != null && !req.headers().contains(HttpHeaders.UPGRADE)
                    && req.uri().equals(APIConstants.WEB_SOCKET_HEALTH_CHECK_PATH)) {
                ctx.fireChannelRead(msg);
                return;
            }

            inboundMessageContext.setUri(req.getUri());
            URI uriTemp = new URI(inboundMessageContext.getUri());
            String apiContextUri = new URI(uriTemp.getScheme(), uriTemp.getAuthority(), uriTemp.getPath(), null,
                    uriTemp.getFragment()).toString();
            apiContextUri = apiContextUri.endsWith("/") ?
                    apiContextUri.substring(0, apiContextUri.length() - 1) :
                    apiContextUri;
            inboundMessageContext.setApiContextUri(apiContextUri);
            inboundMessageContext.setVersion(getVersionFromUrl(inboundMessageContext.getUri()));

            if (log.isDebugEnabled()) {
                log.debug(channelId + " -- Websocket API request [inbound]: " + req.method() + " " + apiContextUri + " " + req.protocolVersion());
                log.debug(channelId + " -- Websocket API request [inbound] : Host: " + req.headers().get(APIConstants.SWAGGER_HOST));
            }
            String tenantDomain;
            if (req.getUri().contains("/t/")) {
                tenantDomain = MultitenantUtils.getTenantDomainFromUrl(req.getUri());
            } else {
                tenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
            }
            inboundMessageContext.setTenantDomain(tenantDomain);
            validateCorsHeaders(ctx, req, inboundMessageContext, tenantDomain);

            // This block is for the context check
            InboundProcessorResponseDTO responseDTO =
                    WebsocketUtil.validateAPIContext(inboundMessageContext, apiContextUri, usageDataPublisher);
            if (!responseDTO.isError()) {
                inboundMessageContext.setElectedAPI(
                        WebsocketUtil.getApi(req.uri(), inboundMessageContext.getTenantDomain()));
            } else {
                handleHandshakeError(channelId, responseDTO, ctx, inboundMessageContext, msg,
                        APISecurityConstants.API_AUTH_INCORRECT_API_RESOURCE_MESSAGE,
                        APISecurityConstants.API_AUTH_INCORRECT_API_RESOURCE,
                        HttpResponseStatus.BAD_REQUEST.code());
            }
            setResourcesMapToContext(inboundMessageContext);

            String useragent = req.headers().get(HttpHeaders.USER_AGENT);

            // '-' is used for empty values to avoid possible errors in DAS side.
            // Required headers are stored one by one as validateOAuthHeader()
            // removes some of the headers from the request
            useragent = useragent != null ? useragent : "-";
            inboundMessageContext.setHeaders(inboundMessageContext.getHeaders().add(HttpHeaders.USER_AGENT, useragent));

            responseDTO = validateOAuthHeader(req, inboundMessageContext);
            if (!responseDTO.isError()) {
                responseDTO = WebsocketUtil.validateDenyPolicies(inboundMessageContext, usageDataPublisher);
                if (!responseDTO.isError()) {
                    if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME
                            .equals(inboundMessageContext.getTenantDomain())) {
                        // carbon-mediation only support websocket invocation from super tenant APIs.
                        // This is a workaround to mimic the the invocation came from super tenant.
                        req.setUri(req.getUri().replaceFirst("/", "-"));
                        String modifiedUri = inboundMessageContext.getUri().replaceFirst("/t/", "-t/");
                        req.setUri(modifiedUri);
                        msg = req;
                    } else {
                        req.setUri(inboundMessageContext.getUri()); // Setting endpoint appended uri
                    }
                    setApiPropertiesMapToChannel(ctx, inboundMessageContext);

                    if (StringUtils.isNotEmpty(inboundMessageContext.getToken())) {
                        String backendJwtHeader = null;
                        JWTConfigurationDto jwtConfigurationDto = ServiceReferenceHolder.getInstance()
                                .getAPIManagerConfiguration().getJwtConfigurationDto();
                        if (jwtConfigurationDto != null) {
                            backendJwtHeader = jwtConfigurationDto.getJwtHeader();
                        }
                        if (StringUtils.isEmpty(backendJwtHeader)) {
                            backendJwtHeader = APIMgtGatewayConstants.WS_JWT_TOKEN_HEADER;
                        }
                        boolean isSSLEnabled = ctx.channel().pipeline().get("ssl") != null;
                        String prefix = null;
                        AxisConfiguration axisConfiguration = ServiceReferenceHolder.getInstance()
                                .getServerConfigurationContext().getAxisConfiguration();
                        TransportOutDescription transportOut;
                        if (isSSLEnabled) {
                            transportOut = axisConfiguration.getTransportOut(APIMgtGatewayConstants.WS_SECURED);
                        } else {
                            transportOut = axisConfiguration.getTransportOut(APIMgtGatewayConstants.WS_NOT_SECURED);
                        }
                        if (transportOut != null
                                && transportOut.getParameter(APIMgtGatewayConstants.WS_CUSTOM_HEADER) != null) {
                            prefix = String.valueOf(
                                    transportOut.getParameter(APIMgtGatewayConstants.WS_CUSTOM_HEADER).getValue());
                        }
                        if (StringUtils.isNotEmpty(prefix)) {
                            backendJwtHeader = prefix + backendJwtHeader;
                        }
                        ((FullHttpRequest) msg).headers().set(backendJwtHeader, inboundMessageContext.getToken());
                    }
                    ctx.fireChannelRead(msg);

                    // publish google analytics data
                    GoogleAnalyticsData.DataBuilder gaData = new GoogleAnalyticsData.DataBuilder(null, null, null, null)
                            .setDocumentPath(inboundMessageContext.getUri()).setDocumentHostName(DataPublisherUtil.getHostAddress()).setSessionControl("end")
                            .setCacheBuster(APIMgtGoogleAnalyticsUtils.getCacheBusterId())
                            .setIPOverride(ctx.channel().remoteAddress().toString());
                    APIMgtGoogleAnalyticsUtils gaUtils = new APIMgtGoogleAnalyticsUtils();
                    gaUtils.init(inboundMessageContext.getTenantDomain());
                    gaUtils.publishGATrackingData(gaData, req.headers().get(HttpHeaders.USER_AGENT),
                            inboundMessageContext.getHeaders().get(HttpHeaders.AUTHORIZATION));
                } else {
                    handleHandshakeError(channelId, responseDTO, ctx, inboundMessageContext, msg,
                            APISecurityConstants.API_BLOCKED_MESSAGE, APISecurityConstants.API_BLOCKED,
                            HttpResponseStatus.FORBIDDEN.code());
                }
            } else {
                handleHandshakeError(channelId, responseDTO, ctx, inboundMessageContext, msg,
                        APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE,
                        APISecurityConstants.API_AUTH_INVALID_CREDENTIALS, HttpResponseStatus.UNAUTHORIZED.code());
            }
        } else if (msg instanceof CloseWebSocketFrame) {
            //remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().remove(channelId);
            //if the inbound frame is a closed frame, throttling, analytics will not be published.
            ctx.fireChannelRead(msg);
        } else if (msg instanceof PingWebSocketFrame || msg instanceof PongWebSocketFrame) {
            //if the inbound frame is a ping/pong frame, throttling, analytics will not be published.
            ctx.fireChannelRead(msg);
        } else if (msg instanceof WebSocketFrame) {
            InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
            if (APIConstants.APITransportType.GRAPHQL.toString()
                    .equals(inboundMessageContext.getElectedAPI().getApiType()) && msg instanceof TextWebSocketFrame) {
                // Authenticate and handle GraphQL subscription requests
                 responseDTO = graphQLRequestProcessor.handleRequest((WebSocketFrame) msg,
                        ctx, inboundMessageContext, usageDataPublisher);
                if (responseDTO.isError()) {
                    handleWebsocketFrameRequestError(responseDTO, channelId, ctx, msg);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else {
                // If not a GraphQL API (Only a WebSocket API)
                responseDTO = inboundMessageContext.isJWTToken() ?
                        WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext) :
                        WebsocketUtil.authenticateOAuthToken(responseDTO, inboundMessageContext.getApiKey(),
                                inboundMessageContext);
                if (!responseDTO.isError()) {
                    // Validate the deny policies are applied to the API when there are no authentication errors
                    responseDTO = WebsocketUtil
                            .validateDenyPolicies(inboundMessageContext, usageDataPublisher);
                    // Check whether the error is now present after deny policies validation
                    if (responseDTO.isError()) {
                        handleWebsocketFrameRequestError(responseDTO, channelId, ctx, msg);
                    } else {
                        WebSocketThrottleResponseDTO throttleResponseDTO = WebsocketUtil
                                .doThrottle(ctx, (WebSocketFrame) msg, null, inboundMessageContext);
                        if (throttleResponseDTO.isThrottled()) {
                            ReferenceCountUtil.release(msg);
                            if (APIUtil.isAnalyticsEnabled()) {
                                WebsocketUtil.publishWSThrottleEvent(inboundMessageContext, usageDataPublisher,
                                        throttleResponseDTO.getThrottledOutReason());
                            }
                            ctx.writeAndFlush(new TextWebSocketFrame("Websocket frame throttled out"));
                            if (log.isDebugEnabled()) {
                                log.debug(channelId + " -- Websocket API request [inbound] : Inbound Websocket frame is throttled. " + ctx.channel().toString());
                            }
                        } else {
                            handleWSRequestSuccess(ctx, msg, inboundMessageContext, usageDataPublisher);
                        }
                    }
                } else {
                    handleWebsocketFrameRequestError(responseDTO, channelId, ctx, msg);
                }
            }
        }
    }

    /**
     * Handle error flow in handshake phase.
     *
     * @param channelId              Channel Id of the web socket connection
     * @param responseDTO            InboundProcessorResponseDTO
     * @param ctx                    ChannelHandlerContext
     * @param inboundMessageContext  InboundMessageContext
     * @param msg                    WebsocketFrame that was received
     * @param errorMessage           Error message
     * @param errorCode              Error code
     * @param httpResponseStatusCode Http response status code
     */
    private void handleHandshakeError(String channelId, InboundProcessorResponseDTO responseDTO,
            ChannelHandlerContext ctx, InboundMessageContext inboundMessageContext, Object msg, String errorMessage,
            int errorCode, int httpResponseStatusCode) throws APISecurityException {
        ReferenceCountUtil.release(msg);
        InboundMessageContextDataHolder.getInstance().removeInboundMessageContextForConnection(channelId);
        if (StringUtils.isEmpty(responseDTO.getErrorMessage())) {
            responseDTO.setErrorMessage(errorMessage);
        }
        responseDTO.setErrorCode(httpResponseStatusCode);
        WebsocketUtil.sendHandshakeErrorMessage(ctx, inboundMessageContext, responseDTO, errorMessage, errorCode);
    }

    private void validateCorsHeaders(ChannelHandlerContext ctx, FullHttpRequest req,
                                     InboundMessageContext inboundMessageContext, String tenantDomain) throws APISecurityException, AxisFault {
        // Current implementation supports validating only the 'origin' header
        String requestOrigin = req.headers().get(HttpHeaderNames.ORIGIN);
        // Don't validate the 'origin' header if it's not present in the request
        if (requestOrigin == null) {
            return;
        }
        CORSConfiguration corsConfiguration = getCORSConfiguration(ctx, req, inboundMessageContext);
        if (corsConfiguration == null || !corsConfiguration.isCorsConfigurationEnabled()) {
            return;
        }
        String allowedOrigin = assessAndGetAllowedOrigin(requestOrigin,
                    corsConfiguration.getAccessControlAllowOrigins());
        if (allowedOrigin == null) {
            //For additional cors validation corsSequence will be mediated once the default cors validation is failed
            MessageContext messageContext = createSynapseMessageContext(tenantDomain);
            Mediator corsSequence = getCorsSequence(messageContext);
            if (corsSequence != null) {
                messageContext.setProperty(APIConstants.CORS_CONFIGURATION_ENABLED, isCorsEnabled());
                //Setting origin from the request to the message context
                messageContext.setProperty(APIConstants.WS_ORIGIN, requestOrigin);
                //Introducing the boolean property to handle origin validation in the sequence level
                messageContext.setProperty(APIConstants.WS_CORS_ORIGIN_SUCCESS,false);
                corsSequence.mediate(messageContext);
                boolean wsCorsOriginSuccess = (Boolean) messageContext.getProperty(APIConstants.WS_CORS_ORIGIN_SUCCESS);
                if (wsCorsOriginSuccess){
                    return;
                }
            }
            handleCORSValidationFailure(ctx, req);
        }
    }

    private CORSConfiguration getCORSConfiguration(ChannelHandlerContext ctx, FullHttpRequest req,
                                                   InboundMessageContext inboundMessageContext)
            throws APISecurityException {
        if (!APIUtil.isCORSValidationEnabledForWS()) {
            return new CORSConfiguration(false, null,false,null, null);
        }
        String errorMessage;
        SubscriptionDataStore datastore = SubscriptionDataHolder.getInstance()
                .getTenantSubscriptionStore(inboundMessageContext.getTenantDomain());
        Set<String> allowedOriginsConfigured = WebsocketUtil.getAllowedOriginsConfigured();
        if (datastore != null) {
            API api = datastore.getApiByContextAndVersion(inboundMessageContext.getApiContextUri(),
                    inboundMessageContext.getVersion());
            if (api == null && APIConstants.DEFAULT_WEBSOCKET_VERSION.equals(inboundMessageContext.getVersion())) {
                // for websocket default version.
                api = datastore.getDefaultApiByContext(inboundMessageContext.getApiContextUri());
            }
            if (api != null) {
                List<String> allowedOrigins = new ArrayList<>(allowedOriginsConfigured);
                CORSConfiguration corsConfiguration = api.getCORSConfiguration();
                if (corsConfiguration != null) {
                    allowedOrigins.addAll(corsConfiguration.getAccessControlAllowOrigins());
                    corsConfiguration.setAccessControlAllowOrigins(allowedOrigins);
                    return corsConfiguration;
                } else {
                    return new CORSConfiguration(true, allowedOrigins, false, null, null);
                }
            } else {
                errorMessage = "API with context: " + inboundMessageContext.getApiContextUri() + " and version: "
                        + inboundMessageContext.getVersion() + " not found in Subscription datastore.";
            }
        } else {
             errorMessage = "Subscription datastore is not initialized for tenant domain "
                    + inboundMessageContext.getTenantDomain();
        }
        log.error(errorMessage);
        handleHandshakeError(ctx.channel().id().asLongText(), new InboundProcessorResponseDTO(), ctx,
                inboundMessageContext, req, errorMessage,
                APISecurityConstants.CORS_ORIGIN_HEADER_VALIDATION_FAILED,
                HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
        return null;
    }

    private void handleCORSValidationFailure(ChannelHandlerContext ctx,
                                             FullHttpRequest req) throws APISecurityException {
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
        ctx.writeAndFlush(httpResponse);
        ctx.close();
        log.warn("Validation of CORS origin header failed for WS request on: " + req.uri());
        throw new APISecurityException(APISecurityConstants.CORS_ORIGIN_HEADER_VALIDATION_FAILED,
                APISecurityConstants.CORS_ORIGIN_HEADER_VALIDATION_FAILED_MESSAGE);
    }

    private String assessAndGetAllowedOrigin(String origin, Collection<String> allowedOrigins) {
        if (allowedOrigins.contains("*")) {
            return "*";
        } else if (allowedOrigins.contains(origin)) {
            return origin;
        } else if (origin != null) {
            for (String allowedOrigin : allowedOrigins) {
                if (allowedOrigin.contains("*")) {
                    Pattern pattern = Pattern.compile(allowedOrigin.replace("*", ".*"));
                    Matcher matcher = pattern.matcher(origin);
                    if (matcher.find()) {
                        return origin;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Authenticate request
     *
     * @param req Full Http Request
     * @return true if the access token is valid
     */
    public InboundProcessorResponseDTO validateOAuthHeader(FullHttpRequest req,
                                                           InboundMessageContext inboundMessageContext) throws APISecurityException {

        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();

        try {
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext()
                    .setTenantDomain(inboundMessageContext.getTenantDomain(), true);
            if (!req.headers().contains(WebsocketUtil.authorizationHeader)) {
                QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
                Map<String, List<String>> requestMap = decoder.parameters();
                if (requestMap.containsKey(APIConstants.AUTHORIZATION_QUERY_PARAM_DEFAULT)) {
                    req.headers().add(WebsocketUtil.authorizationHeader,
                            APIConstants.CONSUMER_KEY_SEGMENT + ' ' + requestMap.get(
                                    APIConstants.AUTHORIZATION_QUERY_PARAM_DEFAULT).get(0));
                    removeTokenFromQuery(requestMap, inboundMessageContext);
                } else {
                    return handleEmptyAuthHeader(responseDTO, inboundMessageContext);
                }
            }
            String authorizationHeader = req.headers().get(WebsocketUtil.authorizationHeader);
            String[] auth = null;
            if (authorizationHeader != null) {
                inboundMessageContext.setHeaders(
                        inboundMessageContext.getHeaders().add(HttpHeaders.AUTHORIZATION, authorizationHeader));
                auth = authorizationHeader.split(" ");
            }
            if (authorizationHeader == null || auth.length != 2) {
                handleEmptyAuthHeader(responseDTO, inboundMessageContext);
            } else if (APIConstants.CONSUMER_KEY_SEGMENT.equals(auth[0])) {
                boolean isJwtToken = false;
                inboundMessageContext.setJWTToken(isJwtToken);
                String apiKey = auth[1];
                inboundMessageContext.setApiKey(apiKey);
                if (WebsocketUtil.isRemoveOAuthHeadersFromOutMessage()) {
                    req.headers().remove(WebsocketUtil.authorizationHeader);
                }

                //Initial guess of a JWT token using the presence of a DOT.
                inboundMessageContext.setSignedJWTInfo(null);
                if (StringUtils.isNotEmpty(apiKey) && apiKey.contains(APIConstants.DOT)) {
                    try {
                        // Check if the header part is decoded
                        if (StringUtils.countMatches(apiKey, APIConstants.DOT) != 2) {
                            String errorMessage = "Invalid JWT token. The expected token format is <header.payload.signature> ";
                            return handleInvalidAuthHeader(responseDTO, inboundMessageContext, errorMessage);
                        }
                        inboundMessageContext.setSignedJWTInfo(getSignedJwtInfo(apiKey));
                        String keyManager = ServiceReferenceHolder.getInstance().getJwtValidationService()
                                .getKeyManagerNameIfJwtValidatorExist(inboundMessageContext.getSignedJWTInfo());
                        if (StringUtils.isNotEmpty(keyManager)) {
                            isJwtToken = true;
                            inboundMessageContext.setJWTToken(isJwtToken);
                        }
                    } catch (ParseException e) {
                        String errorMessage = "Not a JWT token. Failed to decode the token header. ";
                        log.error(errorMessage, e);
                        return handleInvalidAuthHeader(responseDTO, inboundMessageContext, errorMessage);
                    } catch (APIManagementException e) {
                        log.error("error while check validation of JWt", e);
                        throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                                APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE);
                    }
                }
                if (isJwtToken) {
                    log.debug("Websocket API request [inbound] : The token was identified as a JWT token");
                    responseDTO = WebsocketUtil.authenticateWSAndGraphQLJWTToken(inboundMessageContext);
                } else {
                    responseDTO = WebsocketUtil.authenticateOAuthToken(responseDTO, apiKey, inboundMessageContext);
                }
                inboundMessageContext.setToken(inboundMessageContext.getInfoDTO().getEndUserToken());
            } else {
                responseDTO.setError(true);
            }
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
        return responseDTO;
    }

    protected APIManagerAnalyticsConfiguration getApiManagerAnalyticsConfiguration() {
        return DataPublisherUtil.getApiManagerAnalyticsConfiguration();
    }

    /**
     * Handle requests with empty authentication headers.
     *
     * @param inboundMessageContext InboundMessageContext
     * @param responseDTO           InboundProcessorResponseDTO
     * @return responseDTO InboundProcessorResponseDTO
     */
    private InboundProcessorResponseDTO handleEmptyAuthHeader(InboundProcessorResponseDTO responseDTO,
                                                              InboundMessageContext inboundMessageContext) {
        String errorMessage = "No Authorization Header or access_token query parameter present";
        log.error(errorMessage + " in request for the websocket context "
                + inboundMessageContext.getApiContextUri());
        responseDTO.setError(true);
        responseDTO = WebsocketUtil.getHandshakeErrorDTO(
                APIMgtGatewayConstants.WEB_SOCKET_API_AUTH_ERROR, errorMessage);
        return responseDTO;
    }

    /**
     * Handle requests with empty authentication headers.
     *
     * @param inboundMessageContext InboundMessageContext
     * @param responseDTO           InboundProcessorResponseDTO
     * @return responseDTO InboundProcessorResponseDTO
     */
    private InboundProcessorResponseDTO handleInvalidAuthHeader(InboundProcessorResponseDTO responseDTO,
                                                                InboundMessageContext inboundMessageContext, String msg) {
        log.error(msg + " in request for the websocket context " + inboundMessageContext.getApiContextUri());
        responseDTO.setError(true);
        responseDTO = WebsocketUtil.getHandshakeErrorDTO(
                APIMgtGatewayConstants.WEB_SOCKET_API_AUTH_ERROR, msg);
        return responseDTO;
    }

    private void removeTokenFromQuery(Map<String, List<String>> parameters,
                                      InboundMessageContext inboundMessageContext) {
        String uri = inboundMessageContext.getUri();
        StringBuilder queryBuilder = new StringBuilder(uri.substring(0, uri.indexOf('?') + 1));

        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            if (!APIConstants.AUTHORIZATION_QUERY_PARAM_DEFAULT.equals(entry.getKey())) {
                queryBuilder.append(entry.getKey()).append('=').append(entry.getValue().get(0)).append('&');
            }
        }

        // remove trailing '?' or '&' from the built string
        uri = queryBuilder.substring(0, queryBuilder.length() - 1);
        inboundMessageContext.setUri(uri);
    }

    private SignedJWTInfo getSignedJwtInfo(String accessToken) throws ParseException {

        String signature = accessToken.split("\\.")[2];
        SignedJWTInfo signedJWTInfo = null;
        Cache gatewaySignedJWTParseCache = CacheProvider.getGatewaySignedJWTParseCache();
        if (gatewaySignedJWTParseCache != null) {
            Object cachedEntry = gatewaySignedJWTParseCache.get(signature);
            if (cachedEntry != null) {
                signedJWTInfo = (SignedJWTInfo) cachedEntry;
            }
            if (signedJWTInfo == null || !signedJWTInfo.getToken().equals(accessToken)) {
                SignedJWT signedJWT = SignedJWT.parse(accessToken);
                JWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();
                signedJWTInfo = new SignedJWTInfo(accessToken, signedJWT, jwtClaimsSet);
                gatewaySignedJWTParseCache.put(signature, signedJWTInfo);
            }
        } else {
            SignedJWT signedJWT = SignedJWT.parse(accessToken);
            JWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();
            signedJWTInfo = new SignedJWTInfo(accessToken, signedJWT, jwtClaimsSet);
        }
        return signedJWTInfo;
    }

    /**
     * Set the resource map with VerbInfoDTOs to the context using URL mappings from the InboundMessageContext.
     *
     * @param inboundMessageContext InboundMessageContext
     */
    private void setResourcesMapToContext(InboundMessageContext inboundMessageContext) {

        List<URLMapping> urlMappings = inboundMessageContext.getElectedAPI().getResources();
        Map<String, ResourceInfoDTO> resourcesMap = inboundMessageContext.getResourcesMap();

        ResourceInfoDTO resourceInfoDTO;
        VerbInfoDTO verbInfoDTO;
        for (URLMapping urlMapping : urlMappings) {
            resourceInfoDTO = resourcesMap.get(urlMapping.getUrlPattern());
            if (resourceInfoDTO == null) {
                resourceInfoDTO = new ResourceInfoDTO();
                resourceInfoDTO.setUrlPattern(urlMapping.getUrlPattern());
                resourceInfoDTO.setHttpVerbs(new LinkedHashSet<>());
                resourcesMap.put(urlMapping.getUrlPattern(), resourceInfoDTO);
            }
            verbInfoDTO = new VerbInfoDTO();
            verbInfoDTO.setHttpVerb(urlMapping.getHttpMethod());
            verbInfoDTO.setAuthType(urlMapping.getAuthScheme());
            verbInfoDTO.setThrottling(urlMapping.getThrottlingPolicy());
            resourceInfoDTO.getHttpVerbs().add(verbInfoDTO);
        }
    }

    /**
     * @param responseDTO InboundProcessorResponseDTO
     * @param channelId   Channel Id of the web socket connection
     * @param ctx         ChannelHandlerContext
     * @param msg         WebsocketFrame that was received
     */
    private void handleWebsocketFrameRequestError(InboundProcessorResponseDTO responseDTO, String channelId,
                                                  ChannelHandlerContext ctx, Object msg) {
        // Release WebsocketFrame
        ReferenceCountUtil.release(msg);
        if (responseDTO.isCloseConnection()) {
            // remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().remove(channelId);
            Attribute<Object> attributes = ctx.channel().attr(AttributeKey.valueOf(API_PROPERTIES));
            if (attributes != null) {
                try {
                    HashMap apiProperties = (HashMap) attributes.get();
                    if (!apiProperties.containsKey(WEB_SC_API_UT)) {
                        apiProperties.put(WEB_SC_API_UT, responseDTO.getErrorCode());
                    }
                } catch (ClassCastException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Unable to cast attributes to a map", e);
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug(channelId + " -- Websocket API request [inbound] : Error while handling Outbound Websocket frame. Closing connection for " + ctx.channel()
                        .toString());
            }
            ctx.writeAndFlush(new CloseWebSocketFrame(responseDTO.getErrorCode(),
                    responseDTO.getErrorMessage() + StringUtils.SPACE + "Connection closed" + "!"));
            ctx.close();
        } else {
            String errorMessage = responseDTO.getErrorResponseString();
            ctx.writeAndFlush(new TextWebSocketFrame(errorMessage));
            if (responseDTO.getErrorCode() == WebSocketApiConstants.FrameErrorConstants.THROTTLED_OUT_ERROR) {
                if (log.isDebugEnabled()) {
                    log.debug(channelId + " -- Websocket API request [inbound] : Inbound Websocket frame is throttled. " + ctx.channel().toString());
                }
            }
        }
    }

    /**
     * @param ctx                   ChannelHandlerContext
     * @param msg                   Message
     * @param inboundMessageContext InboundMessageContext
     * @param usageDataPublisher    APIMgtUsageDataPublisher
     */
    private void handleWSRequestSuccess(ChannelHandlerContext ctx, Object msg,
            InboundMessageContext inboundMessageContext, APIMgtUsageDataPublisher usageDataPublisher) {
        ctx.fireChannelRead(msg);
        long endTime = System.currentTimeMillis();
        long startTime = (long) ctx.channel().attr(AttributeKey.valueOf(APIMgtGatewayConstants.REQUEST_START_TIME))
                .get();
        long serviceTime = endTime - startTime;
        // publish analytics events if analytics is enabled
        if (APIUtil.isAnalyticsEnabled()) {
            WebsocketUtil.publishWSRequestEvent(inboundMessageContext.getUserIP(), true, inboundMessageContext,
                    usageDataPublisher, serviceTime);
        }
    }

    private void setApiPropertiesMapToChannel(ChannelHandlerContext ctx, InboundMessageContext inboundMessageContext) {
        Map<String, Object> apiPropertiesMap = null;
        if (ctx.channel().hasAttr(AttributeKey.valueOf(API_PROPERTIES))) {
            Object propMap = ctx.channel().attr(AttributeKey.valueOf(API_PROPERTIES)).get();
            if (propMap instanceof Map) {
                apiPropertiesMap = (Map<String, Object>) propMap;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Ignoring previous property set as it is not of type Map");
                }
            }
        }
        ctx.channel().attr(AttributeKey.valueOf(API_PROPERTIES)).set(createApiPropertiesMap(inboundMessageContext
                , apiPropertiesMap));
    }

    private Map<String, Object> createApiPropertiesMap(InboundMessageContext inboundMessageContext,
                                                       Map<String, Object> apiPropertiesMap) {

        if (apiPropertiesMap == null) {
            apiPropertiesMap = new HashMap<>();
        }
        API api = inboundMessageContext.getElectedAPI();
        String apiName = api.getApiName();
        String apiVersion = api.getApiVersion();
        apiPropertiesMap.put(APIMgtGatewayConstants.API, apiName);
        apiPropertiesMap.put(APIMgtGatewayConstants.VERSION, apiVersion);
        apiPropertiesMap.put(APIMgtGatewayConstants.API_VERSION, apiName + ":v" + apiVersion);
        apiPropertiesMap.put(APIMgtGatewayConstants.CONTEXT, inboundMessageContext.getApiContextUri());
        apiPropertiesMap.put(APIMgtGatewayConstants.API_TYPE, String.valueOf(APIConstants.ApiTypes.API));
        apiPropertiesMap.put(APIMgtGatewayConstants.HOST_NAME, APIUtil.getHostAddress());

        APIKeyValidationInfoDTO infoDTO = inboundMessageContext.getInfoDTO();
        if (infoDTO != null) {
            apiPropertiesMap.put(APIMgtGatewayConstants.CONSUMER_KEY, infoDTO.getConsumerKey());
            apiPropertiesMap.put(APIMgtGatewayConstants.USER_ID, infoDTO.getEndUserName());
            apiPropertiesMap.put(APIMgtGatewayConstants.API_PUBLISHER, infoDTO.getApiPublisher());
            apiPropertiesMap.put(APIMgtGatewayConstants.END_USER_NAME, infoDTO.getEndUserName());
            apiPropertiesMap.put(APIMgtGatewayConstants.APPLICATION_NAME, infoDTO.getApplicationName());
            apiPropertiesMap.put(APIMgtGatewayConstants.APPLICATION_ID, infoDTO.getApplicationId());
        }
        return apiPropertiesMap;
    }

    public APIMgtUsageDataPublisher getUsageDataPublisher() {
        return usageDataPublisher;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Attribute<Object> attributes = ctx.channel().attr(AttributeKey.valueOf(API_PROPERTIES));
        if (cause instanceof CorruptedWebSocketFrameException && attributes != null) {
            HashMap apiProperties = (HashMap) attributes.get();
            CorruptedWebSocketFrameException corruptedWebSocketFrameException = ((CorruptedWebSocketFrameException) cause);
            apiProperties.put(WEB_SC_API_UT, corruptedWebSocketFrameException.closeStatus().code());
        }
        super.exceptionCaught(ctx, cause);
    }
    private static org.apache.synapse.MessageContext createSynapseMessageContext(String tenantDomain) throws AxisFault {
        org.apache.axis2.context.MessageContext axis2MsgCtx = createAxis2MessageContext();
        ServiceContext svcCtx = new ServiceContext();
        OperationContext opCtx = new OperationContext(new InOutAxisOperation(), svcCtx);
        axis2MsgCtx.setServiceContext(svcCtx);
        axis2MsgCtx.setOperationContext(opCtx);
        if (!tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
            ConfigurationContext tenantConfigCtx =
                    TenantAxisUtils.getTenantConfigurationContext(tenantDomain,
                            axis2MsgCtx.getConfigurationContext());
            axis2MsgCtx.setConfigurationContext(tenantConfigCtx);
            axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, tenantDomain);
        } else {
            axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN,
                    MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        }
        SOAPFactory fac = OMAbstractFactory.getSOAP11Factory();
        SOAPEnvelope envelope = fac.getDefaultEnvelope();
        axis2MsgCtx.setEnvelope(envelope);
        return MessageContextCreatorForAxis2.getSynapseMessageContext(axis2MsgCtx);
    }
    private static org.apache.axis2.context.MessageContext createAxis2MessageContext() {
        org.apache.axis2.context.MessageContext axis2MsgCtx = new org.apache.axis2.context.MessageContext();
        axis2MsgCtx.setMessageID(UIDGenerator.generateURNString());
        axis2MsgCtx.setConfigurationContext(org.wso2.carbon.inbound.endpoint.osgi.service.ServiceReferenceHolder.getInstance().getConfigurationContextService()
                .getServerConfigContext());
        axis2MsgCtx.setProperty(org.apache.axis2.context.MessageContext.CLIENT_API_NON_BLOCKING,
                Boolean.TRUE);
        axis2MsgCtx.setServerSide(true);
        return axis2MsgCtx;
    }
    private Mediator getCorsSequence(MessageContext messageContext) throws AxisFault {
        Mediator corsSequence = messageContext.getSequence(APIConstants.CORS_SEQUENCE_NAME);
        return corsSequence;
    }
    protected boolean isCorsEnabled() {
        return APIUtil.isCORSEnabled();
    }
}

