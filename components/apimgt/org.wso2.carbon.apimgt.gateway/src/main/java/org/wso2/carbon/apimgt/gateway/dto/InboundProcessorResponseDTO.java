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
package org.wso2.carbon.apimgt.gateway.dto;

import org.json.JSONObject;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;

/**
 * DTO class to hold response information during execution of Inbound processors.
 */
public class InboundProcessorResponseDTO {

    boolean isError = false;
    String id; // operation ID for GraphQL APIs
    int errorCode;
    String errorMessage;
    boolean closeConnection = false; // whether to close the connection if during frame validation

    public InboundProcessorResponseDTO() {
    }

    public boolean isError() {
        return isError;
    }

    public void setError(boolean error) {
        isError = error;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setCloseConnection(boolean closeConnection) {
        this.closeConnection = closeConnection;
    }

    public boolean isCloseConnection() {
        return closeConnection;
    }

    public String getErrorResponseString() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_ID, id);
        jsonObject.put(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_TYPE,
                GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_TYPE_ERROR);
        JSONObject payload = new JSONObject();
        payload.put(GraphQLConstants.FrameErrorConstants.ERROR_MESSAGE, errorMessage);
        payload.put(GraphQLConstants.FrameErrorConstants.ERROR_CODE, errorCode);
        jsonObject.put(GraphQLConstants.SubscriptionConstants.PAYLOAD_FIELD_NAME_PAYLOAD, payload);
        return jsonObject.toString();
    }
}
