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
package org.wso2.carbon.apimgt.gateway.handlers;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.gateway.dto.GraphQLOperationDTO;
import org.wso2.carbon.apimgt.impl.dto.APIKeyValidationInfoDTO;
import org.wso2.carbon.apimgt.impl.dto.ResourceInfoDTO;
import org.wso2.carbon.apimgt.impl.jwt.SignedJWTInfo;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;

import java.util.HashMap;
import java.util.Map;

/**
 * Message context to hold information of an intercepted single inbound connection.
 */
public class InboundMessageContext {

    private String tenantDomain;
    private String uri;
    private String apiContextUri;
    private String version;
    private APIKeyValidationInfoDTO infoDTO = new APIKeyValidationInfoDTO();
    private HttpHeaders headers = new DefaultHttpHeaders();
    private String token;
    private API electedAPI = new API();
    private Map<String, ResourceInfoDTO> resourcesMap = new HashMap<>(); // elected API resources
    private SignedJWTInfo signedJWTInfo;
    private String userIP;
    private AuthenticationContext authContext;
    private boolean isJWTToken;
    private String apiKey;

    // Graphql Subscription specific connection context information
    private Map<String, GraphQLOperationDTO> graphQLMsgIdToVerbInfo = new HashMap<>();

    public InboundMessageContext() {
    }

    public void addVerbInfoForGraphQLMsgId(String msgId, GraphQLOperationDTO graphQLOperationDTO) {
        this.graphQLMsgIdToVerbInfo.put(msgId, graphQLOperationDTO);
    }

    public GraphQLOperationDTO getVerbInfoForGraphQLMsgId(String msgId) {
        return this.graphQLMsgIdToVerbInfo.get(msgId);
    }


    public String getTenantDomain() {
        return tenantDomain;
    }

    public void setTenantDomain(String tenantDomain) {
        this.tenantDomain = tenantDomain;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getApiContextUri() {
        return apiContextUri;
    }

    public void setApiContextUri(String apiContextUri) {
        this.apiContextUri = apiContextUri;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public APIKeyValidationInfoDTO getInfoDTO() {
        return infoDTO;
    }

    public void setInfoDTO(APIKeyValidationInfoDTO infoDTO) {
        this.infoDTO = infoDTO;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public void setHeaders(HttpHeaders headers) {
        this.headers = headers;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public API getElectedAPI() {
        return electedAPI;
    }

    public void setElectedAPI(API electedAPI) {
        this.electedAPI = electedAPI;
    }

    public Map<String, ResourceInfoDTO> getResourcesMap() {
        return resourcesMap;
    }

    public void setResourcesMap(Map<String, ResourceInfoDTO> electedAPIResourcesMap) {
        this.resourcesMap = electedAPIResourcesMap;
    }

    public SignedJWTInfo getSignedJWTInfo() {
        return signedJWTInfo;
    }

    public void setSignedJWTInfo(SignedJWTInfo signedJWTInfo) {
        this.signedJWTInfo = signedJWTInfo;
    }

    public String getUserIP() {
        return userIP;
    }

    public void setUserIP(String userIP) {
        this.userIP = userIP;
    }

    public AuthenticationContext getAuthContext() {
        return authContext;
    }

    public void setAuthContext(AuthenticationContext authContext) {
        this.authContext = authContext;
    }

    public boolean isJWTToken() {
        return isJWTToken;
    }

    public void setJWTToken(boolean JWTToken) {
        isJWTToken = JWTToken;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
