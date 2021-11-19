package org.wso2.carbon.apimgt.internal.service.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.dao.GatewayArtifactsMgtDAO;
import org.wso2.carbon.apimgt.internal.service.*;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.wso2.carbon.apimgt.internal.service.dto.SynapseAttributesDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.ws.rs.core.Response;

public class SynapseAttributesApiServiceImpl implements SynapseAttributesApiService {

    private GatewayArtifactsMgtDAO gatewayArtifactsMgtDAO = GatewayArtifactsMgtDAO.getInstance();
    private SynapseAttributesDTO synapseAttributesListDTOS = new SynapseAttributesDTO();
    private static final Log log = LogFactory.getLog(SynapseAttributesApiServiceImpl.class);
    private boolean debugEnabled = log.isDebugEnabled();

    @Override public Response synapseAttributesGet(String apiName, String tenantDomain, String version,
            MessageContext messageContext) {
        List<String> apiIds = null;
        String apiId = null;
        List<String> labels = null;
        try {
            /*
             *  For some scenarios, there will be multiple api ids for same name and version. (ex: When api is
             *  deleted and created using the same name) . We retrive lables for all these ids. labels are
             *  only returned for published apis and not for removed apis. 
             */
            apiIds = gatewayArtifactsMgtDAO.getGatewayAPIId(apiName, version, tenantDomain);
            if (debugEnabled) {
                log.debug("API Ids for " + apiName + ":" + version + " - " + apiIds.toString());
            }
            for (String id : apiIds) {
                apiId = id;
                labels = gatewayArtifactsMgtDAO.getGatewayAPILabels(id);
                if (debugEnabled) {
                    log.debug("Labels - " + labels.toString());
                }
                if (labels != null && !labels.isEmpty()) {
                    break;
                }
            }
            
        } catch (APIManagementException e) {
            JSONObject responseObj = new JSONObject();
            responseObj.put("Message", "Error retrieving apiID and label of  " + apiName+ " from DB");
            String responseStringObj = String.valueOf(responseObj);
            log.error("Error retrieving apiID and label of  " + apiName+ " from DB", e);
            return Response.serverError().entity(responseStringObj).build();
        }
        if (debugEnabled) {
            log.debug("Successfully retrieved artifacts for " + apiName + " from DB");
        }
        synapseAttributesListDTOS.setApiId(apiId);
        synapseAttributesListDTOS.setLabels(labels);
        return Response.ok().entity(synapseAttributesListDTOS).build();
    }
}
