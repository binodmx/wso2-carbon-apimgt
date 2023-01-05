/*
 *  Copyright (c) 2022, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway.handlers;

public class WebSocketApiConstants {

    WebSocketApiConstants() {
    }

    //Constants for Websocket frame error codes and messages
    public static class FrameErrorConstants {
        public static final int API_AUTH_GENERAL_ERROR = 4000;
        public static final String API_AUTH_GENERAL_MESSAGE = "Unclassified Authentication Failure";
        public static final int API_AUTH_INVALID_CREDENTIALS = 4001;
        public static final String API_AUTH_INVALID_TOKEN_MESSAGE = "Invalid Token";
        public static final int THROTTLED_OUT_ERROR = 4003;
        public static final String THROTTLED_OUT_ERROR_MESSAGE = "Websocket frame throttled out";
        public static final String ERROR_CODE = "code";
        public static final String ERROR_MESSAGE = "message";
        public static final int BAD_REQUEST = 4005;
        public static final String BAD_REQUEST_MESSAGE = "Bad request";
    }
}
