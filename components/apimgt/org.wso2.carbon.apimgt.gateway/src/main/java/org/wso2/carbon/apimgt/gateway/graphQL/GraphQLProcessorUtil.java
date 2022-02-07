/*
 *  Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.apimgt.gateway.graphQL;

import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.Entry;
import org.wso2.carbon.apimgt.api.gateway.GraphQLSchemaDTO;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import org.wso2.carbon.apimgt.gateway.handlers.WebsocketUtil;
import org.wso2.carbon.apimgt.gateway.internal.DataHolder;
import org.wso2.carbon.apimgt.impl.definitions.GraphQLSchemaDefinition;

import java.util.ArrayList;
import java.util.List;

public class GraphQLProcessorUtil {

    private static final Log log = LogFactory.getLog(GraphQLProcessorUtil.class);
    private static final String GRAPHQL_IDENTIFIER = "_graphQL";

    /**
     * This method used to extract operation List
     *
     * @param operation              Operation
     * @param typeDefinitionRegistry TypeDefinitionRegistry
     * @param schemaDefinition Schema Definition
     * @return operationList
     */
    public static String getOperationList(OperationDefinition operation, TypeDefinitionRegistry typeDefinitionRegistry,
            String schemaDefinition) {
        String operationList;
        GraphQLSchemaDefinition graphql = new GraphQLSchemaDefinition();
        ArrayList<String> operationArray = new ArrayList<>();
        TypeDefinitionRegistry typeRegistry;

        if (typeDefinitionRegistry != null) {
            typeRegistry = typeDefinitionRegistry;
        } else {
            SchemaParser schemaParser = new SchemaParser();
            typeRegistry = schemaParser.parse(schemaDefinition);
        }

        List<URITemplate> list = graphql.extractGraphQLOperationList(typeRegistry, operation.getOperation().toString());
        ArrayList<String> supportedFields = getSupportedFields(list);

        getNestedLevelOperations(operation.getSelectionSet().getSelections(), supportedFields, operationArray);
        operationList = String.join(",", operationArray);
        return operationList;
    }

    /**
     * This method support to extracted nested level operations
     *
     * @param selectionList   selection List
     * @param supportedFields supportedFields
     * @param operationArray  operationArray
     */
    public static void getNestedLevelOperations(List<Selection> selectionList, ArrayList<String> supportedFields,
            ArrayList<String> operationArray) {
        for (Selection selection : selectionList) {
            if (!(selection instanceof Field)) {
                continue;
            }
            Field levelField = (Field) selection;
            if (!operationArray.contains(levelField.getName()) &&
                    supportedFields.contains(levelField.getName())) {
                operationArray.add(levelField.getName());
                if (log.isDebugEnabled()) {
                    log.debug("Extracted operation: " + levelField.getName());
                }
            }
            if (levelField.getSelectionSet() != null) {
                getNestedLevelOperations(levelField.getSelectionSet().getSelections(), supportedFields, operationArray);
            }
        }
    }

    /**
     * This method helps to extract only supported operation names
     *
     * @param list URITemplates
     * @return supported Fields
     */
    private static ArrayList<String> getSupportedFields(List<URITemplate> list) {
        ArrayList<String> supportedFields = new ArrayList<>();
        for (URITemplate template : list) {
            supportedFields.add(template.getUriTemplate());
        }
        return supportedFields;
    }

    /**
     * Set the GraphQL Schema to the data holder
     *
     * @param inboundMessageContext InboundMessageContext
     * @throws AxisFault
     */
    public static synchronized void setGraphQLSchemaToDataHolder(InboundMessageContext inboundMessageContext)
            throws AxisFault {
        String apiUuid = inboundMessageContext.getElectedAPI().getUuid();
        if (DataHolder.getInstance().getGraphQLSchemaDTOForAPI(apiUuid) == null) {
            // Retrieve the schema from the local entry
            MessageContext messageContext = WebsocketUtil.getSynapseMessageContext(
                    inboundMessageContext.getTenantDomain());
            Entry localEntryObj = (Entry) messageContext.getConfiguration().getLocalRegistry()
                    .get(apiUuid + GRAPHQL_IDENTIFIER);
            if (localEntryObj != null) {
                SchemaParser schemaParser = new SchemaParser();
                String schemaDefinition = localEntryObj.getValue().toString();
                TypeDefinitionRegistry registry = schemaParser.parse(schemaDefinition);
                GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry);
                GraphQLSchemaDTO schemaDTO = new GraphQLSchemaDTO(schema, registry);
                DataHolder.getInstance().addApiToGraphQLSchemaDTO(apiUuid, schemaDTO);
            }
        }
    }
}
