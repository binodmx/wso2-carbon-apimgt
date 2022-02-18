/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * you may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.gateway.internal;

import org.wso2.carbon.apimgt.api.gateway.GraphQLSchemaDTO;

import java.util.HashMap;
import java.util.Map;

public class DataHolder {

    private static final DataHolder Instance = new DataHolder();
    private Map<String, GraphQLSchemaDTO> apiToGraphQLSchemaDTOMap = new HashMap<>();
    private DataHolder() {

    }

    public static DataHolder getInstance() {
        return Instance;
    }

    public Map<String, GraphQLSchemaDTO> getApiToGraphQLSchemaDTOMap() {
        return apiToGraphQLSchemaDTOMap;
    }

    public GraphQLSchemaDTO getGraphQLSchemaDTOForAPI(String apiId) {
        return apiToGraphQLSchemaDTOMap.get(apiId);
    }

    public void addApiToGraphQLSchemaDTO(String apiId, GraphQLSchemaDTO graphQLSchemaDTO) {
        apiToGraphQLSchemaDTOMap.put(apiId, graphQLSchemaDTO);
    }
}
