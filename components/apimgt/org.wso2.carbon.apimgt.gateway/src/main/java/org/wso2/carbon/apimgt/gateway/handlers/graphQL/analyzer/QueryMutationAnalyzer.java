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
package org.wso2.carbon.apimgt.gateway.handlers.graphQL.analyzer;

import graphql.analysis.FieldComplexityCalculator;
import graphql.schema.GraphQLSchema;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpStatus;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.gateway.dto.QueryAnalyzerResponseDTO;
import org.wso2.carbon.apimgt.gateway.handlers.Utils;
import org.wso2.carbon.apimgt.gateway.handlers.graphQL.FieldComplexityCalculatorImpl;
import org.wso2.carbon.apimgt.gateway.graphQL.QueryAnalyzer;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;
import org.wso2.carbon.apimgt.impl.APIConstants;

/**
 * QueryAnalyzer class extension for GraphQL query and mutation operations.
 */
public class QueryMutationAnalyzer extends QueryAnalyzer {

    private static final Log log = LogFactory.getLog(QueryMutationAnalyzer.class);

    public QueryMutationAnalyzer(GraphQLSchema schema) {
        super(schema);
    }

    /**
     * This method returns the maximum query complexity value
     *
     * @param messageContext message context of the request
     * @return maximum query depth value if exists, or -1 to denote no complexity limitation
     */
    private int getMaxQueryDepth(MessageContext messageContext) {

        Object maxQueryDepth = messageContext.getProperty(APIConstants.MAXIMUM_QUERY_DEPTH);
        if (maxQueryDepth != null) {
            int maxDepth = (Integer) maxQueryDepth;
            if (maxDepth > 0) {
                return maxDepth;
            } else {
                log.debug("Maximum query depth value is 0");
                return -1;
            }
        } else {
            log.debug("Maximum query depth not applicable");
            return -1;
        }
    }

    /**
     * This method analyses the query depth
     *
     * @param messageContext message context of the request
     * @param payload        payload of the request
     * @return true, if the query depth does not exceed the maximum value or false, if query depth exceeds the maximum
     */
    public boolean analyseQueryMutationDepth(MessageContext messageContext, String payload) {

        int maxQueryDepth = getMaxQueryDepth(messageContext);
        QueryAnalyzerResponseDTO responseDTO = analyseQueryDepth(maxQueryDepth, payload);
        if (!responseDTO.isSuccess() && !responseDTO.getErrorList().isEmpty()) {
            handleFailure(APISecurityConstants.GRAPHQL_QUERY_TOO_DEEP, messageContext,
                    APISecurityConstants.GRAPHQL_QUERY_TOO_DEEP_MESSAGE, responseDTO.getErrorList().toString());
            log.error(responseDTO.getErrorList().toString());
            return false;
        }
        return true;
    }

    /**
     * This method analyses the query complexity
     *
     * @param messageContext message context of the request
     * @param payload        payload of the request
     * @return true, if query complexity does not exceed the maximum or false, if query complexity exceeds the maximum
     */
    public boolean analyseQueryMutationComplexity(MessageContext messageContext, String payload) {

        FieldComplexityCalculator fieldComplexityCalculator = null;
        try {
            fieldComplexityCalculator = new FieldComplexityCalculatorImpl(
                    (String) messageContext.getProperty(APIConstants.GRAPHQL_ACCESS_CONTROL_POLICY));
        } catch (ParseException e) {
            String errorMessage = "Policy definition parsing failed. ";
            handleFailure(APISecurityConstants.GRAPHQL_INVALID_QUERY, messageContext, errorMessage, errorMessage);
        }
        int maxQueryComplexity = getMaxQueryComplexity(messageContext);
        QueryAnalyzerResponseDTO responseDTO = analyseQueryComplexity(maxQueryComplexity, payload,
                fieldComplexityCalculator);
        if (!responseDTO.isSuccess() && !responseDTO.getErrorList().isEmpty()) {
            handleFailure(APISecurityConstants.GRAPHQL_QUERY_TOO_COMPLEX, messageContext,
                    APISecurityConstants.GRAPHQL_QUERY_TOO_COMPLEX_MESSAGE, responseDTO.getErrorList().toString());
            log.error(responseDTO.getErrorList().toString());
            return false;
        }
        return true;
    }

    /**
     * This method returns the maximum query complexity value
     *
     * @param messageContext message context of the request
     * @return maximum query complexity value if exists, or -1 to denote no complexity limitation
     */
    private int getMaxQueryComplexity(MessageContext messageContext) {

        Object maxQueryComplexity = messageContext.getProperty(APIConstants.MAXIMUM_QUERY_COMPLEXITY);
        if (maxQueryComplexity != null) {
            int maxComplexity = (Integer) maxQueryComplexity;
            if (maxComplexity > 0) {
                return maxComplexity;
            } else {
                log.debug("Maximum query complexity value is 0");
                return -1;
            }
        } else {
            log.debug("Maximum query complexity not applicable");
            return -1;
        }
    }

    /**
     * This method handle the failure
     *
     * @param errorCodeValue   error code of the failure
     * @param messageContext   message context of the request
     * @param errorMessage     error message of the failure
     * @param errorDescription error description of the failure
     */
    public void handleFailure(int errorCodeValue, MessageContext messageContext,
            String errorMessage, String errorDescription) {
        OMElement payload = getFaultPayload(errorCodeValue, errorMessage, errorDescription);
        Utils.setFaultPayload(messageContext, payload);
        Mediator sequence = messageContext.getSequence(APISecurityConstants.GRAPHQL_API_FAILURE_HANDLER);
        if (sequence != null && !sequence.mediate(messageContext)) {
            return;
        }
        Utils.sendFault(messageContext, HttpStatus.SC_BAD_REQUEST);
    }

    /**
     * @param errorCodeValue error code
     * @param message        fault message
     * @param description    description of the fault message
     * @return the OMElement
     */
    private OMElement getFaultPayload(int errorCodeValue, String message, String description) {
        OMFactory fac = OMAbstractFactory.getOMFactory();
        OMNamespace ns = fac.createOMNamespace(APISecurityConstants.API_SECURITY_NS,
                APISecurityConstants.API_SECURITY_NS_PREFIX);
        OMElement payload = fac.createOMElement("fault", ns);

        OMElement errorCode = fac.createOMElement("code", ns);
        errorCode.setText(errorCodeValue + "");
        OMElement errorMessage = fac.createOMElement("message", ns);
        errorMessage.setText(message);
        OMElement errorDetail = fac.createOMElement("description", ns);
        errorDetail.setText(description);

        payload.addChild(errorCode);
        payload.addChild(errorMessage);
        payload.addChild(errorDetail);
        return payload;
    }
}
