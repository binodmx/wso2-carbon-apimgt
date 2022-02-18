package org.wso2.carbon.apimgt.gateway.handlers.graphQL;

import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.FieldComplexityEnvironment;
import graphql.language.Argument;
import graphql.language.IntValue;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.gateway.graphQL.GraphQLConstants;
import org.wso2.carbon.apimgt.gateway.handlers.security.APISecurityConstants;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/**
 * This Class can be used to calculate fields complexity values of GraphQL Query.
 */
public class FieldComplexityCalculatorImpl implements FieldComplexityCalculator {
    private static final Log log = LogFactory.getLog(FieldComplexityCalculatorImpl.class);
    protected JSONParser jsonParser = new JSONParser();
    protected JSONObject policyDefinition;

    public FieldComplexityCalculatorImpl(String accessControlPolicy) throws ParseException {
        if (accessControlPolicy == null) {
            policyDefinition = new JSONObject();
        } else {
            JSONObject jsonObject = (JSONObject) jsonParser.parse(accessControlPolicy);
            policyDefinition = (JSONObject) jsonObject.get(GraphQLConstants.QUERY_ANALYSIS_COMPLEXITY);
        }
    }

    @Override
    public int calculate(FieldComplexityEnvironment fieldComplexityEnvironment, int childComplexity) {
        String fieldName = fieldComplexityEnvironment.getField().getName();
        String parentType = fieldComplexityEnvironment.getParentType().getName();
        List<Argument> argumentList = fieldComplexityEnvironment.getField().getArguments();

        int argumentsValue = getArgumentsValue(argumentList);
        int customFieldComplexity = getCustomComplexity(fieldName, parentType, policyDefinition);
        return (argumentsValue * (customFieldComplexity + childComplexity));
    }

    private int getCustomComplexity(String fieldName, String parentType, JSONObject policyDefinition) {
        JSONObject customComplexity = (JSONObject) policyDefinition.get(parentType);
        if (customComplexity != null && customComplexity.get(fieldName) != null) {
            //TODO:chnge as interger
            return ((Long) customComplexity.get(fieldName)).intValue(); // Returns custom complexity value
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No custom complexity value was assigned for " + fieldName + " under type " + parentType);
            }
            return 1; // Returns default complexity value
        }
    }

    private int getArgumentsValue(List<Argument> argumentList) {
        int argumentValue = 0;
        if (argumentList.size() > 0) {
            for (Argument object : argumentList) {
                String argumentName = object.getName();
                // The below list of slicing arguments (keywords) effect query complexity to multiply by the factor
                // given as the value of the argument.
                List<String> slicingArguments = GraphQLConstants.QUERY_COMPLEXITY_SLICING_ARGS;
                if (slicingArguments.contains(argumentName.toLowerCase(Locale.ROOT))) {
                    BigInteger value = null;
                    if (object.getValue() instanceof IntValue) {
                        value = ((IntValue) object.getValue()).getValue();
                    }
                    int val = 0;
                    if (value != null) {
                        val = value.intValue();
                    }
                    argumentValue = argumentValue + val;
                } else {
                    argumentValue += 1;
                }
            }
        } else {
            argumentValue = 1;
        }
        return argumentValue;
    }

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
