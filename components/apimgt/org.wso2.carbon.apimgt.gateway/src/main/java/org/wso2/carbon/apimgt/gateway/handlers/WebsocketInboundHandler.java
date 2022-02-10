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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.subscription.URLMapping;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.InboundMessageContextDataHolder;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLRequestProcessor;
import org.wso2.carbon.apimgt.gateway.handlers.security.APIKeyValidator;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityUtils;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.jwt.JWTValidator;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.gateway.utils.APIMgtGoogleAnalyticsUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerAnalyticsConfiguration;
import org.wso2.carbon.apimgt.impl.caching.CacheProvider;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.ResourceInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.impl.jwt.SignedJWTInfo;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.apimgt.usage.publisher.DataPublisherUtil;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.ganalytics.publisher.GoogleAnalyticsData;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.net.URI;
import java.text.ParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.cache.Cache;

/**
 * This is a handler which is actually embedded to the netty pipeline which does operations such as
 * authentication and throttling for the websocket handshake and subsequent websocket frames.
 */
public class WebsocketInboundHandler extends ChannelInboundHandlerAdapter {
    private static final Log log = LogFactory.getLog(WebsocketInboundHandler.class);
    private static APIMgtUsageDataPublisher usageDataPublisher;
    private GraphQLRequestProcessor graphQLRequestProcessor = new GraphQLRequestProcessor();

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
            if (!req.headers().contains(HttpHeaders.UPGRADE)) {
                FullHttpResponse httpResponse =
                        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                httpResponse.headers().set("content-type", "text/plain; charset=UTF-8");
                httpResponse.headers().set("content-length", httpResponse.content().readableBytes());
                ctx.writeAndFlush(httpResponse);
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

            if (log.isDebugEnabled()) {
                log.debug("Websocket API apiContextUri = " + apiContextUri);
            }
            if (req.getUri().contains("/t/")) {
                inboundMessageContext.setTenantDomain(MultitenantUtils.getTenantDomainFromUrl(req.getUri()));
            } else {
                inboundMessageContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            }

            inboundMessageContext.setElectedAPI(
                    WebsocketUtil.getApi(req.uri(), inboundMessageContext.getTenantDomain()));
            setResourcesMapToContext(inboundMessageContext);

            String useragent = req.headers().get(HttpHeaders.USER_AGENT);

            // '-' is used for empty values to avoid possible errors in DAS side.
            // Required headers are stored one by one as validateOAuthHeader()
            // removes some of the headers from the request
            useragent = useragent != null ? useragent : "-";
            inboundMessageContext.setHeaders(inboundMessageContext.getHeaders().add(HttpHeaders.USER_AGENT, useragent));

            InboundProcessorResponseDTO responseDTO = validateOAuthHeader(req, inboundMessageContext);
            if (!responseDTO.isError()) {
                if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(inboundMessageContext.getTenantDomain())) {
                    // carbon-mediation only support websocket invocation from super tenant APIs.
                    // This is a workaround to mimic the the invocation came from super tenant.
                    req.setUri(req.getUri().replaceFirst("/", "-"));
                    String modifiedUri = inboundMessageContext.getUri().replaceFirst("/t/", "-t/");
                    req.setUri(modifiedUri);
                    msg = req;
                } else {
                    req.setUri(inboundMessageContext.getUri()); // Setting endpoint appended uri
                }

                if (StringUtils.isNotEmpty(inboundMessageContext.getToken())) {
                    ((FullHttpRequest) msg).headers()
                            .set(APIMgtGatewayConstants.WS_JWT_TOKEN_HEADER, inboundMessageContext.getToken());
                }
                ctx.fireChannelRead(msg);

                // publish google analytics data
                GoogleAnalyticsData.DataBuilder gaData = new GoogleAnalyticsData.DataBuilder(null, null, null,
                        null).setDocumentPath(inboundMessageContext.getUri())
                        .setDocumentHostName(DataPublisherUtil.getHostAddress()).setSessionControl("end")
                        .setCacheBuster(APIMgtGoogleAnalyticsUtils.getCacheBusterId())
                        .setIPOverride(ctx.channel().remoteAddress().toString());
                APIMgtGoogleAnalyticsUtils gaUtils = new APIMgtGoogleAnalyticsUtils();
                gaUtils.init(inboundMessageContext.getTenantDomain());
                gaUtils.publishGATrackingData(gaData, req.headers().get(HttpHeaders.USER_AGENT),
                        inboundMessageContext.getHeaders().get(HttpHeaders.AUTHORIZATION));
            } else {
                InboundMessageContextDataHolder.getInstance().removeInboundMessageContextForConnection(channelId);
                if (StringUtils.isEmpty(responseDTO.getErrorMessage())) {
                    responseDTO.setErrorMessage(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE);
                }
                responseDTO.setErrorCode(HttpResponseStatus.UNAUTHORIZED.code());
                WebsocketUtil.sendInvalidCredentialsMessage(ctx, inboundMessageContext, responseDTO);
            }
        } else if ((msg instanceof CloseWebSocketFrame) || (msg instanceof PingWebSocketFrame)) {
            //remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().remove(channelId);
            //if the inbound frame is a closed frame, throttling, analytics will not be published.
            ctx.fireChannelRead(msg);
        } else if (msg instanceof WebSocketFrame) {

            if (APIConstants.APITransportType.GRAPHQL.toString()
                    .equals(inboundMessageContext.getElectedAPI().getApiType()) && msg instanceof TextWebSocketFrame) {
                // Authenticate and handle GraphQL subscription requests
                InboundProcessorResponseDTO responseDTO = graphQLRequestProcessor.handleRequest((WebSocketFrame) msg,
                        ctx, inboundMessageContext, usageDataPublisher);
                if (responseDTO.isError()) {
                    handleGraphQLRequestError(responseDTO, channelId, ctx);
                } else {
                    ctx.fireChannelRead(msg);
                }
            } else {
                // If not a GraphQL API (Only a WebSocket API)
                WebSocketThrottleResponseDTO throttleResponseDTO =
                        WebsocketUtil.doThrottle(ctx, (WebSocketFrame) msg, null, inboundMessageContext);
                if (throttleResponseDTO.isThrottled()) {
                    if (APIUtil.isAnalyticsEnabled()) {
                        WebsocketUtil.publishWSThrottleEvent(inboundMessageContext, usageDataPublisher,
                                throttleResponseDTO.getThrottledOutReason());
                    }
                    ctx.writeAndFlush(new TextWebSocketFrame("Websocket frame throttled out"));
                    if (log.isDebugEnabled()) {
                        log.debug("Inbound Websocket frame is throttled. " + ctx.channel().toString());
                    }
                } else {
                    handleWSRequestSuccess(ctx, msg, inboundMessageContext, usageDataPublisher);
                }
            }
        }
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
            inboundMessageContext.setVersion(getVersionFromUrl(inboundMessageContext.getUri()));
            if (!req.headers().contains(HttpHeaders.AUTHORIZATION)) {
                QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
                Map<String, List<String>> requestMap = decoder.parameters();
                if (requestMap.containsKey(APIConstants.AUTHORIZATION_QUERY_PARAM_DEFAULT)) {
                    req.headers().add(HttpHeaders.AUTHORIZATION,
                            APIConstants.CONSUMER_KEY_SEGMENT + ' ' + requestMap.get(
                                    APIConstants.AUTHORIZATION_QUERY_PARAM_DEFAULT).get(0));
                    removeTokenFromQuery(requestMap, inboundMessageContext);
                } else {
                    String errorMessage = "No Authorization Header or access_token query parameter present";
                    log.error(errorMessage + " in request for the websocket context "
                            + inboundMessageContext.getApiContextUri());
                    responseDTO.setError(true);
                    responseDTO = GraphQLRequestProcessor.getHandshakeErrorDTO(
                            GraphQLConstants.HandshakeErrorConstants.API_AUTH_ERROR, errorMessage);
                    return responseDTO;
                }
            }
            String authorizationHeader = req.headers().get(HttpHeaders.AUTHORIZATION);
            inboundMessageContext.setHeaders(
                    inboundMessageContext.getHeaders().add(HttpHeaders.AUTHORIZATION, authorizationHeader));
            String[] auth = authorizationHeader.split(" ");
            if (APIConstants.CONSUMER_KEY_SEGMENT.equals(auth[0])) {
                boolean isJwtToken = false;
                inboundMessageContext.setJWTToken(isJwtToken);
                String apiKey = auth[1];
                inboundMessageContext.setApiKey(apiKey);
                if (WebsocketUtil.isRemoveOAuthHeadersFromOutMessage()) {
                    req.headers().remove(HttpHeaders.AUTHORIZATION);
                }

                //Initial guess of a JWT token using the presence of a DOT.
                inboundMessageContext.setSignedJWTInfo(null);
                if (StringUtils.isNotEmpty(apiKey) && apiKey.contains(APIConstants.DOT)) {
                    try {
                        // Check if the header part is decoded
                        if (StringUtils.countMatches(apiKey, APIConstants.DOT) != 2) {
                            log.debug("Invalid JWT token. The expected token format is <header.payload.signature>");
                            throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS,
                                    "Invalid JWT token");
                        }
                        inboundMessageContext.setSignedJWTInfo(getSignedJwtInfo(apiKey));
                        String keyManager = ServiceReferenceHolder.getInstance().getJwtValidationService()
                                .getKeyManagerNameIfJwtValidatorExist(inboundMessageContext.getSignedJWTInfo());
                        if (StringUtils.isNotEmpty(keyManager)) {
                            isJwtToken = true;
                            inboundMessageContext.setJWTToken(isJwtToken);
                        }
                    } catch (ParseException e) {
                        log.debug("Not a JWT token. Failed to decode the token header.", e);
                    } catch (APIManagementException e) {
                        log.error("error while check validation of JWt", e);
                        throw new APISecurityException(APISecurityConstants.API_AUTH_GENERAL_ERROR,
                                APISecurityConstants.API_AUTH_GENERAL_ERROR_MESSAGE);
                    }
                }
                // Find the authentication scheme based on the token type
                String apiVersion;
                boolean isDefaultVersion = false;
                if ((inboundMessageContext.getApiContextUri().startsWith("/" + inboundMessageContext.getVersion())
                        || inboundMessageContext.getApiContextUri().startsWith(
                        "/t/" + inboundMessageContext.getTenantDomain() + "/" + inboundMessageContext.getVersion()))) {
                    apiVersion = APIConstants.DEFAULT_WEBSOCKET_VERSION;
                    inboundMessageContext.setVersion(apiVersion);
                    isDefaultVersion = true;
                }
                if (isJwtToken) {
                    log.debug("The token was identified as a JWT token");

                    if (APIConstants.APITransportType.GRAPHQL.toString()
                            .equals(inboundMessageContext.getElectedAPI().getApiType())) {
                        responseDTO = graphQLRequestProcessor.authenticateGraphQLJWTToken(inboundMessageContext);
                    } else {
                        responseDTO = authenticateWSJWTToken(inboundMessageContext, isDefaultVersion);
                    }
                } else {
                    responseDTO = WebsocketUtil.authenticateOAuthToken(responseDTO, apiKey, inboundMessageContext);
                }
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
     */
    private void handleGraphQLRequestError(InboundProcessorResponseDTO responseDTO, String channelId,
            ChannelHandlerContext ctx) {
        if (responseDTO.isCloseConnection()) {
            // remove inbound message context from data holder
            InboundMessageContextDataHolder.getInstance().getInboundMessageContextMap().remove(channelId);
            if (log.isDebugEnabled()) {
                log.debug("Error while handling Outbound Websocket frame. Closing connection for " + ctx.channel()
                        .toString());
            }
            ctx.writeAndFlush(new CloseWebSocketFrame(responseDTO.getErrorCode(),
                    responseDTO.getErrorMessage() + StringUtils.SPACE + "Connection closed" + "!"));
            ctx.close();
        } else {
            String errorMessage = responseDTO.getErrorResponseString();
            ctx.writeAndFlush(new TextWebSocketFrame(errorMessage));
            if (responseDTO.getErrorCode() == GraphQLConstants.FrameErrorConstants.THROTTLED_OUT_ERROR) {
                if (log.isDebugEnabled()) {
                    log.debug("Inbound Websocket frame is throttled. " + ctx.channel().toString());
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
        // publish analytics events if analytics is enabled
        if (APIUtil.isAnalyticsEnabled()) {
            WebsocketUtil.publishWSRequestEvent(inboundMessageContext.getUserIP(), true, inboundMessageContext,
                    usageDataPublisher);
        }
    }

    /**
     * @param inboundMessageContext InboundMessageContext
     * @param isDefaultVersion      Is default version or not
     * @return responseDTO
     * @throws APISecurityException If an error occurs while authenticating the WebSocket API
     */
    private InboundProcessorResponseDTO authenticateWSJWTToken(InboundMessageContext inboundMessageContext,
            Boolean isDefaultVersion) throws APISecurityException {
        InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
        AuthenticationContext authenticationContext = new JWTValidator(
                new APIKeyValidator()).authenticateForWSAndGraphQL(inboundMessageContext.getSignedJWTInfo(),
                inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion());
        inboundMessageContext.setAuthContext(authenticationContext);
        if (!WebsocketUtil.validateAuthenticationContext(inboundMessageContext, isDefaultVersion)) {
            responseDTO.setError(true);
        }
        return responseDTO;
    }

    public APIMgtUsageDataPublisher getUsageDataPublisher() {
        return usageDataPublisher;
    }
}

