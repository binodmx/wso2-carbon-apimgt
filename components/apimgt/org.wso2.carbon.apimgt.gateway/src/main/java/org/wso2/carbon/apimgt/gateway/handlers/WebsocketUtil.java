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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;
import org.apache.axiom.util.UIDGenerator;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.OperationContext;
import org.apache.axis2.context.ServiceContext;
import org.apache.axis2.description.InOutAxisOperation;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.MessageContextCreatorForAxis2;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.dto.GraphQLOperationDTO;
import org.wso2.carbon.apimgt.gateway.dto.InboundProcessorResponseDTO;
import org.wso2.carbon.apimgt.gateway.dto.WebSocketThrottleResponseDTO;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLProcessor;
import org.wso2.carbon.apimgt.gateway.handlers.security.APIKeyValidator;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityException;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityUtils;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.handlers.security.jwt.JWTValidator;
import org.wso2.carbon.apimgt.gateway.handlers.throttling.APIThrottleConstants;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.caching.CacheProvider;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.VerbInfoDTO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.usage.publisher.APIMgtUsageDataPublisher;
import org.wso2.carbon.apimgt.usage.publisher.DataPublisherUtil;
import org.wso2.carbon.apimgt.usage.publisher.dto.ExecutionTimeDTO;
import org.wso2.carbon.apimgt.usage.publisher.dto.RequestResponseStreamDTO;
import org.wso2.carbon.apimgt.usage.publisher.dto.ThrottlePublisherDTO;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.cache.Cache;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class WebsocketUtil extends GraphQLProcessor {
	private static Logger log = LoggerFactory.getLogger(WebsocketUtil.class);
	private static boolean removeOAuthHeadersFromOutMessage = true;
	private static boolean gatewayTokenCacheEnabled = false;
	public static Set<String> allowedOriginsConfigured = new HashSet<>();
	public static String authorizationHeader = null;

	static {
		initParams();
	}

	/**
	 * initialize static parameters of WebsocketUtil class
	 *
	 */
	protected static void initParams() {
		APIManagerConfiguration config = ServiceReferenceHolder.getInstance().getAPIManagerConfiguration();
		String cacheEnabled = config.getFirstProperty(APIConstants.GATEWAY_TOKEN_CACHE_ENABLED);
		if (cacheEnabled != null) {
			gatewayTokenCacheEnabled = Boolean.parseBoolean(cacheEnabled);
		}
		String value = config.getFirstProperty(APIConstants.REMOVE_OAUTH_HEADERS_FROM_MESSAGE);
		if (value != null) {
			removeOAuthHeadersFromOutMessage = Boolean.parseBoolean(value);
		}

		if (authorizationHeader == null) {
			try {
				authorizationHeader = APIUtil
						.getOAuthConfigurationFromAPIMConfig(APIConstants.AUTHORIZATION_HEADER);
				if (authorizationHeader == null) {
					authorizationHeader = HttpHeaders.AUTHORIZATION;
				}
			} catch (APIManagementException e) {
				log.error("Error while reading authorization header from APIM configurations", e);
			}
		}

		//initialize CORS Configs
		if (APIUtil.isCORSValidationEnabledForWS()) {
			String allowedOriginsConfigured = APIUtil.getAllowedOrigins();
			if (!allowedOriginsConfigured.isEmpty()) {
				WebsocketUtil.allowedOriginsConfigured = new HashSet<>(Arrays.asList(allowedOriginsConfigured.split(",")));
			}
		}
	}

	public static boolean isRemoveOAuthHeadersFromOutMessage() {
		return removeOAuthHeadersFromOutMessage;
	}

	/**
	 * validate access token via cache
	 *
	 * @param apiKey access token
	 * @param cacheKey key of second level cache
	 * @return APIKeyValidationInfoDTO
	 */
	public static APIKeyValidationInfoDTO validateCache(String apiKey, String cacheKey) {

		//Get the access token from the first level cache.
		String cachedToken = (String) getGatewayTokenCache().get(apiKey);

		//If the access token exists in the first level cache.
		if (cachedToken != null) {
			APIKeyValidationInfoDTO info =
					(APIKeyValidationInfoDTO) getGatewayKeyCache().get(cacheKey);

			if (info != null) {
				if (APIUtil.isAccessTokenExpired(info)) {
					info.setAuthorized(false);
					// in cache, if token is expired  remove cache entry.
					getGatewayKeyCache().remove(cacheKey);
					//Remove from the first level token cache as well.
					getGatewayTokenCache().remove(apiKey);
				}
				return info;
			}
		}

		return null;
	}

	/**
	 * write to cache
	 *
	 * @param info
	 * @param apiKey
	 * @param cacheKey
	 */
	public static void putCache(APIKeyValidationInfoDTO info, String apiKey, String cacheKey) {

		//Get the tenant domain of the API that is being invoked.
		String tenantDomain =
				PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantDomain();

		//Add to first level Token Cache.
		getGatewayTokenCache().put(apiKey, tenantDomain);
		//Add to Key Cache.
		getGatewayKeyCache().put(cacheKey, info);

		//If this is NOT a super-tenant API that is being invoked
		if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
			//Add the tenant domain as a reference to the super tenant cache so we know from which tenant cache
			//to remove the entry when the need occurs to clear this particular cache entry.
			try {
				PrivilegedCarbonContext.startTenantFlow();
				PrivilegedCarbonContext.getThreadLocalCarbonContext().
						setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);

				getGatewayTokenCache().put(apiKey, tenantDomain);
			} finally {
				PrivilegedCarbonContext.endTenantFlow();
			}
		}
	}

	protected static Cache getGatewayKeyCache() {
		return CacheProvider.getGatewayKeyCache();
	}

	protected static Cache getGatewayTokenCache() {
		return CacheProvider.getGatewayTokenCache();
	}

	public static boolean isGatewayTokenCacheEnabled() {
		return gatewayTokenCacheEnabled;
	}

	/**
	 * check if the request is throttled
	 *
	 * @param resourceLevelThrottleKey
	 * @param subscriptionLevelThrottleKey
	 * @param applicationLevelThrottleKey
	 * @return true if request is throttled out
	 */
	public static boolean isThrottled(String resourceLevelThrottleKey, String subscriptionLevelThrottleKey,
			String applicationLevelThrottleKey) {
		boolean isApiLevelThrottled = ServiceReferenceHolder.getInstance().getThrottleDataHolder()
				.isAPIThrottled(resourceLevelThrottleKey);
		boolean isSubscriptionLevelThrottled = ServiceReferenceHolder.getInstance().getThrottleDataHolder()
				.isThrottled(subscriptionLevelThrottleKey);
		boolean isApplicationLevelThrottled = ServiceReferenceHolder.getInstance().getThrottleDataHolder()
				.isThrottled(applicationLevelThrottleKey);
		return (isApiLevelThrottled || isApplicationLevelThrottled || isSubscriptionLevelThrottled);
	}

	/**
	 * Check if request is throttled out from API level or resource level tier.
	 *
	 * @param resourceLevelThrottleKey API/Resource level throttle key
	 * @return throttled out or not
	 */
	public static boolean isApiResourceLevelThrottled(String resourceLevelThrottleKey) {

		return ServiceReferenceHolder.getInstance().getThrottleDataHolder()
				.isAPIThrottled(resourceLevelThrottleKey);
	}

	/**
	 * Check if request is throttled out from Subscription level.
	 *
	 * @param subscriptionLevelThrottleKey Subscription level throttle key
	 * @return throttled out or not
	 */
	public static boolean isSubscriptionLevelThrottled(String subscriptionLevelThrottleKey) {

		return ServiceReferenceHolder.getInstance().getThrottleDataHolder()
				.isThrottled(subscriptionLevelThrottleKey);
	}

	/**
	 * Check if request is throttled out from Application level.
	 *
	 * @param applicationLevelThrottleKey Application level throttle key
	 * @return throttled out or not
	 */
	public static boolean isApplicationLevelThrottled(String applicationLevelThrottleKey) {

		return ServiceReferenceHolder.getInstance().getThrottleDataHolder()
				.isThrottled(applicationLevelThrottleKey);
	}

	public static String getAccessTokenCacheKey(String accessToken, String apiContext) {
		return accessToken + ':' + apiContext;
	}

	/**
	 * Get the name of the matching api for the request path.
	 *
	 * @param requestPath  The request path
	 * @param tenantDomain Tenant domain
	 * @return The selected API
	 */
	public static API getApi(String requestPath, String tenantDomain) {
		TreeMap<String, API> selectedAPIS = Utils.getSelectedAPIList(
				requestPath, tenantDomain);
		if (selectedAPIS.size() > 0) {
			String selectedPath = selectedAPIS.firstKey();
			API selectedAPI = selectedAPIS.get(selectedPath);
			return selectedAPI;
		}
		return null;
	}

	/**
	 * Validates AuthenticationContext and set APIKeyValidationInfoDTO to InboundMessageContext.
	 *
	 * @param inboundMessageContext InboundMessageContext
	 * @return true if authenticated
	 */
	public static boolean validateAuthenticationContext(InboundMessageContext inboundMessageContext,
			Boolean isDefaultVersion) {

		String uri = inboundMessageContext.getUri();
		AuthenticationContext authenticationContext = inboundMessageContext.getAuthContext();
		if (authenticationContext == null || !authenticationContext.isAuthenticated()) {
			return false;
		}
		// The information given by the AuthenticationContext is set to an APIKeyValidationInfoDTO object
		// so to feed information analytics and throttle data publishing
		APIKeyValidationInfoDTO info = new APIKeyValidationInfoDTO();
		info.setAuthorized(authenticationContext.isAuthenticated());
		info.setApplicationTier(authenticationContext.getApplicationTier());
		info.setTier(authenticationContext.getTier());
		info.setSubscriberTenantDomain(authenticationContext.getSubscriberTenantDomain());
		info.setSubscriber(authenticationContext.getSubscriber());
		info.setStopOnQuotaReach(authenticationContext.isStopOnQuotaReach());
		info.setApiName(authenticationContext.getApiName());
		info.setApplicationId(authenticationContext.getApplicationId());
		info.setType(authenticationContext.getKeyType());
		info.setApiPublisher(authenticationContext.getApiPublisher());
		info.setApplicationName(authenticationContext.getApplicationName());
		info.setConsumerKey(authenticationContext.getConsumerKey());
		info.setEndUserName(authenticationContext.getUsername());
		info.setApiTier(authenticationContext.getApiTier());
		info.setEndUserToken(authenticationContext.getCallerToken());
		info.setGraphQLMaxDepth(authenticationContext.getGraphQLMaxDepth());
		info.setGraphQLMaxComplexity(authenticationContext.getGraphQLMaxComplexity());

		//This prefix is added for synapse to dispatch this request to the specific sequence
		if (APIConstants.API_KEY_TYPE_PRODUCTION.equals(info.getType())) {
			if (isDefaultVersion) {
				uri = "/_PRODUCTION_" + uri + "/" + authenticationContext.getApiVersion();
			} else {
				uri = "/_PRODUCTION_" + uri;
			}
		} else if (APIConstants.API_KEY_TYPE_SANDBOX.equals(info.getType())) {
			if (isDefaultVersion) {
				uri = "/_SANDBOX_" + uri + "/" + authenticationContext.getApiVersion();
			} else {
				uri = "/_SANDBOX_" + uri;
			}
		}
		inboundMessageContext.setUri(uri);
		if (isDefaultVersion) {
			inboundMessageContext.setVersion(authenticationContext.getApiVersion());
		}

		inboundMessageContext.setInfoDTO(info);
		return authenticationContext.isAuthenticated();
	}

	/**
	 * Send authentication failure message
	 *
	 * @param ctx                   Channel handler context
	 * @param inboundMessageContext InboundMessageContext
	 * @param responseDTO           InboundProcessorResponseDTO
	 * @throws APISecurityException
	 */
	public static void sendInvalidCredentialsMessage(ChannelHandlerContext ctx,
			InboundMessageContext inboundMessageContext, InboundProcessorResponseDTO responseDTO) throws APISecurityException {

		String errorMessage = APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE;
		FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.valueOf(responseDTO.getErrorCode()),
				Unpooled.copiedBuffer(errorMessage, CharsetUtil.UTF_8));
		httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, httpResponse.content().readableBytes());
		ctx.writeAndFlush(httpResponse);
		if (log.isDebugEnabled()) {
			log.debug("Authentication Failure for the websocket context: " + inboundMessageContext.getApiContextUri());
		}
		throw new APISecurityException(APISecurityConstants.API_AUTH_INVALID_CREDENTIALS,
				APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE);
	}

	/**
	 * @param tenantDomain Tenant domain
	 * @return Synapse message context
	 * @throws AxisFault If an error occurs while retrieving the synapse message context
	 */
	public static MessageContext getSynapseMessageContext(String tenantDomain) throws AxisFault {

		org.apache.axis2.context.MessageContext axis2MsgCtx = createAxis2MessageContext();
		ServiceContext svcCtx = new ServiceContext();
		OperationContext opCtx = new OperationContext(new InOutAxisOperation(), svcCtx);
		axis2MsgCtx.setServiceContext(svcCtx);
		axis2MsgCtx.setOperationContext(opCtx);
		if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
			ConfigurationContext tenantConfigCtx = TenantAxisUtils.getTenantConfigurationContext(tenantDomain,
					axis2MsgCtx.getConfigurationContext());
			axis2MsgCtx.setConfigurationContext(tenantConfigCtx);
			axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, tenantDomain);
		} else {
			axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
		}
		return MessageContextCreatorForAxis2.getSynapseMessageContext(axis2MsgCtx);
	}

	private static org.apache.axis2.context.MessageContext createAxis2MessageContext() {

		org.apache.axis2.context.MessageContext axis2MsgCtx = new org.apache.axis2.context.MessageContext();
		axis2MsgCtx.setMessageID(UIDGenerator.generateURNString());
		axis2MsgCtx.setConfigurationContext(
				org.wso2.carbon.inbound.endpoint.osgi.service.ServiceReferenceHolder.getInstance()
						.getConfigurationContextService().getServerConfigContext());
		axis2MsgCtx.setProperty(org.apache.axis2.context.MessageContext.CLIENT_API_NON_BLOCKING, Boolean.TRUE);
		axis2MsgCtx.setServerSide(true);
		return axis2MsgCtx;
	}

	/**
	 * Checks if the request is throttled.
	 *
	 * @param ctx                   ChannelHandlerContext
	 * @param msg                   WebSocketFrame
	 * @param verbInfoDTO           VerbInfoDTO
	 * @param inboundMessageContext InboundMessageContext
	 * @return WebSocketThrottleResponseDTO
	 * @throws APIManagementException
	 */
	public static WebSocketThrottleResponseDTO doThrottle(ChannelHandlerContext ctx, WebSocketFrame msg,
														  VerbInfoDTO verbInfoDTO,
														  InboundMessageContext inboundMessageContext) {

		WebSocketThrottleResponseDTO webSocketThrottleResponseDTO = new WebSocketThrottleResponseDTO();
		APIKeyValidationInfoDTO infoDTO = inboundMessageContext.getInfoDTO();
		String apiName = infoDTO.getApiName();
		String apiContext = inboundMessageContext.getApiContextUri();
		String apiVersion = inboundMessageContext.getVersion();
		String applicationLevelTier = infoDTO.getApplicationTier();

		String apiLevelTier = infoDTO.getApiTier() == null && verbInfoDTO == null ? APIConstants.UNLIMITED_TIER
				: infoDTO.getApiTier();
		String apiLevelThrottleKey = apiContext + ":" + apiVersion;
		String subscriptionLevelTier = infoDTO.getTier();
		String resourceLevelTier;
		String resourceLevelThrottleKey;

		// If API level throttle policy is present then it will apply and no resource level policy will apply for it
		if (StringUtils.isNotEmpty(apiLevelTier) || verbInfoDTO == null) {
			resourceLevelThrottleKey = apiLevelThrottleKey;
			resourceLevelTier = apiLevelTier;
		} else {
			resourceLevelThrottleKey = verbInfoDTO.getRequestKey();
			resourceLevelTier = verbInfoDTO.getThrottling();
		}

		String authorizedUser;
		if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equalsIgnoreCase(infoDTO.getSubscriberTenantDomain())) {
			authorizedUser = infoDTO.getSubscriber() + "@" + infoDTO.getSubscriberTenantDomain();
		} else {
			authorizedUser = infoDTO.getSubscriber();
		}

		String appTenant = infoDTO.getSubscriberTenantDomain();
		String apiTenant = inboundMessageContext.getTenantDomain();
		String appId = infoDTO.getApplicationId();
		String applicationLevelThrottleKey = appId + ":" + authorizedUser;
		String subscriptionLevelThrottleKey = appId + ":" + apiContext + ":" + apiVersion + ":" + subscriptionLevelTier;
		String messageId = UIDGenerator.generateURNString();
		String remoteIP = getRemoteIP(ctx);
		if (log.isDebugEnabled()) {
			log.debug("Remote IP address : " + remoteIP);
		}
		if (remoteIP.indexOf(":") > 0) {
			remoteIP = remoteIP.substring(1, remoteIP.indexOf(":"));
		}
		JSONObject jsonObMap = new JSONObject();
		if (remoteIP != null && remoteIP.length() > 0) {
			try {
				InetAddress address = APIUtil.getAddress(remoteIP);
				if (address instanceof Inet4Address) {
					jsonObMap.put(APIThrottleConstants.IP, APIUtil.ipToLong(remoteIP));
				} else if (address instanceof Inet6Address) {
					jsonObMap.put(APIThrottleConstants.IPv6, APIUtil.ipToBigInteger(remoteIP));
				}
			} catch (UnknownHostException e) {
				//ignore the error and log it
				log.error("Error while parsing host IP " + remoteIP, e);
			}
		}
		jsonObMap.put(APIThrottleConstants.MESSAGE_SIZE, msg.content().capacity());
		try {
			PrivilegedCarbonContext.startTenantFlow();
			PrivilegedCarbonContext.getThreadLocalCarbonContext()
					.setTenantDomain(inboundMessageContext.getTenantDomain(), true);
			boolean isApiLevelThrottled = isApiResourceLevelThrottled(resourceLevelThrottleKey);
			boolean isSubscriptionLevelThrottled = isSubscriptionLevelThrottled(subscriptionLevelThrottleKey);
			boolean isApplicationLevelThrottled = isApplicationLevelThrottled(applicationLevelThrottleKey);
			if (isApiLevelThrottled || isApplicationLevelThrottled || isSubscriptionLevelThrottled) {
				webSocketThrottleResponseDTO.setThrottled(true);
				String throttledOutReason = APIConstants.THROTTLE_OUT_REASON_SOFT_LIMIT_EXCEEDED;
				if (isApplicationLevelThrottled) {
					throttledOutReason = APIThrottleConstants.APPLICATION_LIMIT_EXCEEDED;
				}
				if (isSubscriptionLevelThrottled) {
					throttledOutReason = APIThrottleConstants.SUBSCRIPTION_LIMIT_EXCEEDED;
				}
				if (isApiLevelThrottled) {
					if (StringUtils.isNotEmpty(apiLevelTier)) {
						throttledOutReason = APIThrottleConstants.API_LIMIT_EXCEEDED;
					} else {
						throttledOutReason = APIThrottleConstants.RESOURCE_LIMIT_EXCEEDED;
					}
				}
				webSocketThrottleResponseDTO.setThrottledOutReason(throttledOutReason);
				return webSocketThrottleResponseDTO;
			}
		} finally {
			PrivilegedCarbonContext.endTenantFlow();
		}
		Object[] objects = new Object[]{messageId, applicationLevelThrottleKey, applicationLevelTier,
				apiLevelThrottleKey, apiLevelTier, subscriptionLevelThrottleKey, subscriptionLevelTier,
				resourceLevelThrottleKey, resourceLevelTier, authorizedUser, apiContext, apiVersion, appTenant,
				apiTenant, appId, apiName, jsonObMap.toString()};
		org.wso2.carbon.databridge.commons.Event event = new org.wso2.carbon.databridge.commons.Event(
				"org.wso2.throttle.request.stream:1.0.0", System.currentTimeMillis(), null, null, objects);
		if (ServiceReferenceHolder.getInstance().getThrottleDataPublisher() == null) {
			log.error("Cannot publish events to traffic manager because ThrottleDataPublisher "
					+ "has not been initialised");
			webSocketThrottleResponseDTO.setThrottled(false);
			return webSocketThrottleResponseDTO;
		}
		ServiceReferenceHolder.getInstance().getThrottleDataPublisher().getDataPublisher().tryPublish(event);
		webSocketThrottleResponseDTO.setThrottled(false);
		return webSocketThrottleResponseDTO;
	}

	public static String getRemoteIP(ChannelHandlerContext ctx) {
		return ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
	}

	/**
	 * Publish request event to analytics server.
	 *
	 * @param requestPublisherDTO   Resource detail populated Request Publish Stream DTO
	 * @param clientIp              client's IP Address
	 * @param isThrottledOut        request is throttled out or not
	 * @param inboundMessageContext InboundMessageContext
	 * @param usageDataPublisher    APIMgtUsageDataPublisher
	 */
	public static void publishRequestEvent(RequestResponseStreamDTO requestPublisherDTO, String clientIp,
										   boolean isThrottledOut, InboundMessageContext inboundMessageContext,
										   APIMgtUsageDataPublisher usageDataPublisher) {

		long requestTime = System.currentTimeMillis();
		String useragent = inboundMessageContext.getHeaders().get(HttpHeaders.USER_AGENT);

		try {
			APIKeyValidationInfoDTO infoDTO = inboundMessageContext.getInfoDTO();
			String appOwner = infoDTO.getSubscriber();
			String keyType = infoDTO.getType();
			String correlationID = UUID.randomUUID().toString();

			requestPublisherDTO.setApiName(infoDTO.getApiName());
			requestPublisherDTO.setApiCreator(infoDTO.getApiPublisher());
			requestPublisherDTO.setApiCreatorTenantDomain(MultitenantUtils.getTenantDomain(infoDTO.getApiPublisher()));
			requestPublisherDTO.setApiVersion(infoDTO.getApiName() + ':' + inboundMessageContext.getVersion());
			requestPublisherDTO.setApplicationId(infoDTO.getApplicationId());
			requestPublisherDTO.setApplicationName(infoDTO.getApplicationName());
			requestPublisherDTO.setApplicationOwner(appOwner);
			requestPublisherDTO.setUserIp(clientIp);
			requestPublisherDTO.setApplicationConsumerKey(infoDTO.getConsumerKey());
			//context will always be empty as this method will call only for WebSocketFrame and url is null
			requestPublisherDTO.setApiContext(inboundMessageContext.getApiContextUri());
			requestPublisherDTO.setThrottledOut(isThrottledOut);
			requestPublisherDTO.setApiHostname(DataPublisherUtil.getHostAddress());
			requestPublisherDTO.setRequestTimestamp(requestTime);
			requestPublisherDTO.setUserAgent(useragent);
			requestPublisherDTO.setUsername(infoDTO.getEndUserName());
			requestPublisherDTO.setUserTenantDomain(inboundMessageContext.getTenantDomain());
			requestPublisherDTO.setApiTier(infoDTO.getTier());
			requestPublisherDTO.setApiVersion(inboundMessageContext.getVersion());
			requestPublisherDTO.setMetaClientType(keyType);
			requestPublisherDTO.setCorrelationID(correlationID);
			requestPublisherDTO.setUserAgent(useragent);
			requestPublisherDTO.setCorrelationID(correlationID);
			requestPublisherDTO.setGatewayType(APIMgtGatewayConstants.GATEWAY_TYPE);
			requestPublisherDTO.setLabel(APIMgtGatewayConstants.SYNAPDE_GW_LABEL);
			requestPublisherDTO.setProtocol("WebSocket");
			requestPublisherDTO.setDestination("-");
			requestPublisherDTO.setBackendTime(0);
			requestPublisherDTO.setResponseCacheHit(false);
			requestPublisherDTO.setResponseCode(0);
			requestPublisherDTO.setResponseSize(0);
			requestPublisherDTO.setServiceTime(0);
			requestPublisherDTO.setResponseTime(0);
			ExecutionTimeDTO executionTime = new ExecutionTimeDTO();
			executionTime.setBackEndLatency(0);
			executionTime.setOtherLatency(0);
			executionTime.setRequestMediationLatency(0);
			executionTime.setResponseMediationLatency(0);
			executionTime.setSecurityLatency(0);
			executionTime.setThrottlingLatency(0);
			requestPublisherDTO.setExecutionTime(executionTime);
			usageDataPublisher.publishEvent(requestPublisherDTO);
		} catch (Exception e) {
			// flow should not break if event publishing failed
			log.error("Cannot publish event. " + e.getMessage(), e);
		}
	}

	/**
	 * Publish WS request event to analytics server.
	 *
	 * @param clientIp              client's IP Address
	 * @param isThrottledOut        request is throttled out or not
	 * @param inboundMessageContext InboundMessageContext
	 * @param usageDataPublisher    APIMgtUsageDataPublisher
	 */
	public static void publishWSRequestEvent(String clientIp, boolean isThrottledOut,
											 InboundMessageContext inboundMessageContext,
											 APIMgtUsageDataPublisher usageDataPublisher) {

		RequestResponseStreamDTO requestPublisherDTO = new RequestResponseStreamDTO();
		requestPublisherDTO.setApiMethod("-");
		requestPublisherDTO.setApiResourcePath("-");
		requestPublisherDTO.setApiResourceTemplate("-");
		publishRequestEvent(requestPublisherDTO, clientIp, isThrottledOut, inboundMessageContext, usageDataPublisher);
	}

	/**
	 * Publish GraphQL request event to analytics server.
	 *
	 * @param clientIp              client's IP Address
	 * @param isThrottledOut        request is throttled out or not
	 * @param inboundMessageContext InboundMessageContext
	 * @param usageDataPublisher    APIMgtUsageDataPublisher
	 */
	public static void publishGraphQLSubscriptionEvent(String clientIp, boolean isThrottledOut,
													   InboundMessageContext inboundMessageContext,
													   APIMgtUsageDataPublisher usageDataPublisher,
													   String subscriptionOperation) {

		RequestResponseStreamDTO requestPublisherDTO = new RequestResponseStreamDTO();
		requestPublisherDTO.setApiMethod(GraphQLConstants.SubscriptionConstants.HTTP_METHOD_NAME);
		requestPublisherDTO.setApiResourcePath("/");
		requestPublisherDTO.setApiResourceTemplate(subscriptionOperation);
		publishRequestEvent(requestPublisherDTO, clientIp, isThrottledOut, inboundMessageContext, usageDataPublisher);
	}

	/**
	 * Publish Websocket throttle events.
	 *
	 * @param inboundMessageContext InboundMessageContext
	 * @param usageDataPublisher    APIMgtUsageDataPublisher
	 * @param throttleOutReason 	Throttle Out Reason
	 */
	public static void publishWSThrottleEvent(InboundMessageContext inboundMessageContext,
											  APIMgtUsageDataPublisher usageDataPublisher,
											  String throttleOutReason) {

		ThrottlePublisherDTO throttlePublisherDTO = new ThrottlePublisherDTO();
		throttlePublisherDTO.setApiResourceTemplate("-");
		throttlePublisherDTO.setApiMethod("-");
		publishThrottleEvent(inboundMessageContext, usageDataPublisher, throttlePublisherDTO, throttleOutReason);
	}

	/**
	 * Publish GraphQL subscription throttle events.
	 *
	 * @param inboundMessageContext InboundMessageContext
	 * @param usageDataPublisher    APIMgtUsageDataPublisher
	 * @param subscriptionOperation Subscription operation name
	 * @param throttleOutReason 	Throttle Out Reason
	 */
	public static void publishGraphQLSubThrottleEvent(InboundMessageContext inboundMessageContext,
													  APIMgtUsageDataPublisher usageDataPublisher,
													  String subscriptionOperation, String throttleOutReason) {

		ThrottlePublisherDTO throttlePublisherDTO = new ThrottlePublisherDTO();
		throttlePublisherDTO.setApiResourceTemplate(subscriptionOperation);
		throttlePublisherDTO.setApiMethod(GraphQLConstants.SubscriptionConstants.HTTP_METHOD_NAME);
		publishThrottleEvent(inboundMessageContext, usageDataPublisher, throttlePublisherDTO, throttleOutReason);
	}

	/**
	 * Publish throttle events.
	 *
	 * @param inboundMessageContext InboundMessageContext
	 * @param usageDataPublisher    APIMgtUsageDataPublisher
	 * @param throttlePublisherDTO  ThrottlePublisherDTO which already populated with operation data
	 */
	public static void publishThrottleEvent(InboundMessageContext inboundMessageContext,
			APIMgtUsageDataPublisher usageDataPublisher, ThrottlePublisherDTO throttlePublisherDTO,
											String throttleOutReason) {
		long requestTime = System.currentTimeMillis();
		String correlationID = UUID.randomUUID().toString();
		try {
			APIKeyValidationInfoDTO infoDTO = inboundMessageContext.getInfoDTO();
			throttlePublisherDTO.setKeyType(infoDTO.getType());
			throttlePublisherDTO.setTenantDomain(inboundMessageContext.getTenantDomain());
			throttlePublisherDTO.setApiname(infoDTO.getApiName());
			throttlePublisherDTO.setVersion(inboundMessageContext.getVersion());
			throttlePublisherDTO.setContext(inboundMessageContext.getApiContextUri());
			throttlePublisherDTO.setApiCreator(infoDTO.getApiPublisher());
			throttlePublisherDTO.setApiCreatorTenantDomain(MultitenantUtils.getTenantDomain(infoDTO.getApiPublisher()));
			throttlePublisherDTO.setApplicationName(infoDTO.getApplicationName());
			throttlePublisherDTO.setApplicationId(infoDTO.getApplicationId());
			throttlePublisherDTO.setSubscriber(infoDTO.getSubscriber());
			throttlePublisherDTO.setThrottledTime(requestTime);
			throttlePublisherDTO.setGatewayType(APIMgtGatewayConstants.GATEWAY_TYPE);
			throttlePublisherDTO.setThrottledOutReason(throttleOutReason);
			throttlePublisherDTO.setUsername(infoDTO.getEndUserName());
			throttlePublisherDTO.setCorrelationID(correlationID);
			throttlePublisherDTO.setHostName(DataPublisherUtil.getHostAddress());
			throttlePublisherDTO.setAccessToken("-");
			usageDataPublisher.publishEvent(throttlePublisherDTO);
		} catch (Exception e) {
			// flow should not break if event publishing failed
			log.error("Cannot publish event. " + e.getMessage(), e);
		}
	}

	/**
	 * Authenticates JWT token in incoming GraphQL subscription requests.
	 *
	 * @param inboundMessageContext InboundMessageContext
	 * @return InboundProcessorResponseDTO
	 */
	public static InboundProcessorResponseDTO authenticateWSAndGraphQLJWTToken(InboundMessageContext inboundMessageContext) {

		InboundProcessorResponseDTO responseDTO = new InboundProcessorResponseDTO();
		AuthenticationContext authenticationContext;
		JWTValidator jwtValidator = new JWTValidator(new APIKeyValidator());
		try {
			PrivilegedCarbonContext.startTenantFlow();
			PrivilegedCarbonContext.getThreadLocalCarbonContext()
					.setTenantDomain(inboundMessageContext.getTenantDomain(), true);
			authenticationContext = jwtValidator.authenticateForWSAndGraphQL(inboundMessageContext.getSignedJWTInfo(),
					inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion());
			boolean isDefaultVersion = false;
			inboundMessageContext.setAuthContext(authenticationContext);
			if ((inboundMessageContext.getApiContextUri().startsWith("/" + inboundMessageContext.getVersion())
					|| inboundMessageContext.getApiContextUri().startsWith(
					"/t/" + inboundMessageContext.getTenantDomain() + "/" + inboundMessageContext.getVersion()))) {
				inboundMessageContext.setVersion(APIConstants.DEFAULT_WEBSOCKET_VERSION);
				isDefaultVersion = true;
			}
			if (!WebsocketUtil.validateAuthenticationContext(inboundMessageContext, isDefaultVersion)) {
				responseDTO = getFrameErrorDTO(GraphQLConstants.FrameErrorConstants.API_AUTH_INVALID_CREDENTIALS,
						APISecurityConstants.API_AUTH_INVALID_CREDENTIALS_MESSAGE, true);
			}
		} catch (APISecurityException e) {
			log.error(String.valueOf(GraphQLConstants.FrameErrorConstants.API_AUTH_INVALID_CREDENTIALS), e);
			responseDTO = getFrameErrorDTO(GraphQLConstants.FrameErrorConstants.API_AUTH_INVALID_CREDENTIALS,
					e.getMessage(), true);
		} finally {
			PrivilegedCarbonContext.endTenantFlow();
		}
		return responseDTO;
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
	 * Authenticates OAuth token in incoming GraphQL subscription requests/responses or in WebSocket Handshake requests.
	 *
	 * @param responseDTO           InboundProcessorResponseDTO
	 * @param apiKey                API key (token)
	 * @param inboundMessageContext InboundMessageContext
	 * @return InboundProcessorResponseDTO
	 * @throws APISecurityException if an error occurs while retrieving API key data for client
	 */
	public static InboundProcessorResponseDTO authenticateOAuthToken(InboundProcessorResponseDTO responseDTO,
			String apiKey, InboundMessageContext inboundMessageContext) throws APISecurityException {
		String cacheKey;
		APIKeyValidationInfoDTO info = null;
		boolean prefixAdded = false;

		log.debug("The token was identified as an OAuth token");
		//If the key have already been validated
		if (WebsocketUtil.isGatewayTokenCacheEnabled()) {
			cacheKey = WebsocketUtil.getAccessTokenCacheKey(apiKey, inboundMessageContext.getUri());
			info = WebsocketUtil.validateCache(apiKey, cacheKey);
			if (info != null) {

				//This prefix is added for synapse to dispatch this request to the specific sequence
				if (APIConstants.API_KEY_TYPE_PRODUCTION.equals(info.getType())) {
					inboundMessageContext.setUri("/_PRODUCTION_" + inboundMessageContext.getUri());
					prefixAdded = true;
				} else if (APIConstants.API_KEY_TYPE_SANDBOX.equals(info.getType())) {
					inboundMessageContext.setUri("/_SANDBOX_" + inboundMessageContext.getUri());
					prefixAdded = true;
				}

				inboundMessageContext.setInfoDTO(info);
				responseDTO.setError(info.isAuthorized());
			}
		}
		String keyValidatorClientType = APISecurityUtils.getKeyValidatorClientType();
		if (APIConstants.API_KEY_VALIDATOR_WS_CLIENT.equals(keyValidatorClientType)) {
			info = getApiKeyDataForWSClient(apiKey, inboundMessageContext.getTenantDomain(),
					inboundMessageContext.getApiContextUri(), inboundMessageContext.getVersion());
		} else {
			responseDTO.setError(true);
		}
		if (info == null || !info.isAuthorized()) {
			responseDTO.setError(true);
		}
		if (info.getApiName() != null && info.getApiName().contains("*")) {
			String[] str = info.getApiName().split("\\*");
			inboundMessageContext.setVersion(str[1]);
			inboundMessageContext.setUri("/" + str[1]);
			info.setApiName(str[0]);
		}
		if (WebsocketUtil.isGatewayTokenCacheEnabled()) {
			cacheKey = WebsocketUtil.getAccessTokenCacheKey(apiKey, inboundMessageContext.getUri());
			WebsocketUtil.putCache(info, apiKey, cacheKey);
		}
		//This prefix is added for synapse to dispatch this request to the specific sequence
		if (!prefixAdded) {
			if (APIConstants.API_KEY_TYPE_PRODUCTION.equals(info.getType())) {
				inboundMessageContext.setUri("/_PRODUCTION_" + inboundMessageContext.getUri());
			} else if (APIConstants.API_KEY_TYPE_SANDBOX.equals(info.getType())) {
				inboundMessageContext.setUri("/_SANDBOX_" + inboundMessageContext.getUri());
			}
		}
		inboundMessageContext.setToken(info.getEndUserToken());
		inboundMessageContext.setInfoDTO(info);
		responseDTO.setError(false);
		return responseDTO;
	}

	protected static APIKeyValidationInfoDTO getApiKeyDataForWSClient(String key, String domain, String apiContextUri,
			String apiVersion) throws APISecurityException {

		return new WebsocketWSClient().getAPIKeyData(apiContextUri, apiVersion, key, domain);
	}

	/**
	 * Check if messages is valid subscription operation execution result. Payload should consist 'type' field and its
	 * value equal to either of 'data' or 'next'. The value 'data' is used in 'subscriptions-transport-ws'
	 * protocol and 'next' is used in 'graphql-ws' protocol.
	 *
	 * @param graphQLMsg GraphQL message
	 * @return true if valid operation
	 */
	private static boolean checkIfSubscribeMessageResponse(JSONObject graphQLMsg) {
		return graphQLMsg.getString(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE) != null
				&& GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ARRAY_FOR_DATA.contains(
				graphQLMsg.getString(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE));
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
}
