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
package org.wso2.carbon.apimgt.gateway.graphQL;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GraphQLConstants {
    public static final String QUERY_ANALYSIS_COMPLEXITY = "complexity";
    public static final List<String> QUERY_COMPLEXITY_SLICING_ARGS = Collections.unmodifiableList(
            Arrays.asList("first", "last", "limit"));

    public static class FrameErrorConstants {
        public static final int API_AUTH_INVALID_CREDENTIALS = 4001;
        public static final String API_AUTH_INVALID_CREDENTIALS_MESSAGE = "Invalid Credentials";
        public static final int RESOURCE_FORBIDDEN_ERROR = 4002;
        public static final String RESOURCE_FORBIDDEN_ERROR_MESSAGE = "User NOT authorized to access the resource";
        public static final int THROTTLED_OUT_ERROR = 4003;
        public static final String THROTTLED_OUT_ERROR_MESSAGE = "Websocket frame throttled out";
        public static final int INTERNAL_SERVER_ERROR = 4004;
        public static final int BAD_REQUEST = 4005;
        public static final int GRAPHQL_QUERY_TOO_DEEP = 4020;
        public static final String GRAPHQL_QUERY_TOO_DEEP_MESSAGE = "QUERY TOO DEEP";
        public static final int GRAPHQL_QUERY_TOO_COMPLEX = 4021;
        public static final String GRAPHQL_QUERY_TOO_COMPLEX_MESSAGE = "QUERY TOO COMPLEX";
        public static final int GRAPHQL_INVALID_QUERY = 4022;
        public static final String GRAPHQL_INVALID_QUERY_MESSAGE = "INVALID QUERY";
        public static final String ERROR_CODE = "code";
        public static final String ERROR_MESSAGE = "message";
    }

    // GraphQL Constants related to GraphQL Subscription operations
    public static class SubscriptionConstants {
        public static final String HTTP_METHOD_NAME = "SUBSCRIPTION";
        public static final String PAYLOAD_FIELD_NAME_TYPE = "type";
        public static final List<String> PAYLOAD_FIELD_NAME_ARRAY_FOR_SUBSCRIBE =
                Collections.unmodifiableList(Arrays.asList("start", "subscribe"));
        public static final List<String> PAYLOAD_FIELD_NAME_ARRAY_FOR_DATA =
                Collections.unmodifiableList(Arrays.asList("data", "next"));
        public static final String PAYLOAD_FIELD_NAME_PAYLOAD = "payload";
        public static final String PAYLOAD_FIELD_NAME_QUERY = "query";
        public static final String PAYLOAD_FIELD_NAME_ID = "id";
        public static final String PAYLOAD_FIELD_TYPE_ERROR = "error";
    }
}
