/*
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.apimgt.impl.executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.FaultGatewaysException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.APIType;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.APIVersionComparator;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.governance.api.common.util.CheckListItemBean;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.GenericArtifactManager;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.governance.api.util.GovernanceUtils;
import org.wso2.carbon.governance.registry.extensions.aspects.utils.LifecycleConstants;
import org.wso2.carbon.governance.registry.extensions.interfaces.Execution;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.jdbc.handlers.RequestContext;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.wso2.carbon.apimgt.impl.APIConstants.PUBLISH_IN_PRIVATE_JET_MODE;

/**
 * This class is an implementation of the
 * interface {@link org.wso2.carbon.governance.registry.extensions.interfaces.Execution}
 * This class consists methods that will create, prototype, publish, block, deprecate and
 * retire  an API to API Manager.
 * <p/>
 * This executor used to publish a service to API store as a API.
 *
 * @see org.wso2.carbon.governance.registry.extensions.interfaces.Execution
 */
public class APIExecutor implements Execution {

    Log log = LogFactory.getLog(APIExecutor.class);

    /**
     * This method is called when the execution class is initialized.
     * All the execution classes are initialized only once.
     *
     * @param parameterMap Static parameter map given by the user.
     *                     These are the parameters that have been given in the
     *                     lifecycle configuration as the parameters of the executor.
     */
    public void init(Map parameterMap) {
    }

    /**
     * @param context      The request context that was generated from the registry core.
     *                     The request context contains the resource, resource path and other
     *                     variables generated during the initial call.
     * @param currentState The current lifecycle state.
     * @param targetState  The target lifecycle state.
     * @return Returns whether the execution was successful or not.
     */
    public boolean execute(RequestContext context, String currentState, String targetState) {
        boolean executed = false;
        String user = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        String domain = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();

        String userWithDomain = user;
        if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(domain)) {
            userWithDomain = user + APIConstants.EMAIL_DOMAIN_SEPARATOR + domain;
        } else {
            userWithDomain = APIUtil.appendTenantDomainForEmailUsernames(user, domain);
        }
        userWithDomain = APIUtil.replaceEmailDomainBack(userWithDomain);
        if (log.isDebugEnabled()) {
            log.debug("Corrected username with domain = " + userWithDomain);
        }

        try {
            String tenantUserName = MultitenantUtils.getTenantAwareUsername(userWithDomain);
            int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager().getTenantId(domain);

            GenericArtifactManager artifactManager = APIUtil
                    .getArtifactManager(context.getSystemRegistry(), APIConstants.API_KEY);
            Registry registry = ServiceReferenceHolder.getInstance().
                    getRegistryService().getGovernanceUserRegistry(tenantUserName, tenantId);
            Resource apiResource = context.getResource();
            String artifactId = apiResource.getUUID();
            if (artifactId == null) {
                return false;
            }
            GenericArtifact apiArtifact = artifactManager.getGenericArtifact(artifactId);

            String type = apiArtifact.getAttribute(APIConstants.API_OVERVIEW_TYPE);

            APIProvider apiProvider = APIManagerFactory.getInstance().getAPIProvider(userWithDomain);

            if (APIConstants.API_PRODUCT.equals(type)) {
                executed = true;
            } else {
                API api = APIUtil.getAPIForPublishing(apiArtifact, registry);
                return changeLifeCycle(context, api, apiResource, registry, apiProvider, apiArtifact, targetState,
                        artifactManager);
            }
        } catch (RegistryException e) {
            log.error("Failed to get the generic artifact while executing APIExecutor. ", e);
            context.setProperty(LifecycleConstants.EXECUTOR_MESSAGE_KEY,
                    "APIManagementException:" + e.getMessage());
        } catch (APIManagementException e) {
            log.error("Failed to publish service to API store while executing APIExecutor. ", e);
            context.setProperty(LifecycleConstants.EXECUTOR_MESSAGE_KEY,
                    "APIManagementException:" + e.getMessage());
        } catch (FaultGatewaysException e) {
            log.error("Failed to publish service gateway while executing APIExecutor. ", e);
            context.setProperty(LifecycleConstants.EXECUTOR_MESSAGE_KEY,
                    "FaultGatewaysException:" + e.getFaultMap());
        } catch (UserStoreException e) {
            log.error("Failed to get tenant Id while executing APIExecutor. ", e);
            context.setProperty(LifecycleConstants.EXECUTOR_MESSAGE_KEY,
                    "UserStoreException:" + e.getMessage());
        } catch (org.wso2.carbon.registry.api.RegistryException e) {
            log.error("Failed to get lifecycle status. ", e);
            context.setProperty(LifecycleConstants.EXECUTOR_MESSAGE_KEY,
                    "RegistryException:" + e.getMessage());
        }
        return executed;
    }

    private boolean changeLifeCycle(RequestContext context, API api, Resource apiResource, Registry registry,
            APIProvider apiProvider, GenericArtifact apiArtifact, String targetState,
            GenericArtifactManager artifactManager)
            throws APIManagementException, FaultGatewaysException, RegistryException {
        boolean executed;

        String oldStatus = APIUtil.getLcStateFromArtifact(apiArtifact);
        String newStatus = (targetState != null) ? targetState.toUpperCase() : targetState;

        boolean isCurrentCreatedOrPrototyped = APIConstants.CREATED.equals(oldStatus) ||
                APIConstants.PROTOTYPED.equals(oldStatus);
        boolean isStateTransitionToPublished = isCurrentCreatedOrPrototyped && APIConstants.PUBLISHED.equals(newStatus);
        if (newStatus != null) { //only allow the executor to be used with default LC states transition
            //check only the newStatus so this executor can be used for LC state change from
            //custom state to default api state
            if (isStateTransitionToPublished) {
                Set<Tier> tiers = api.getAvailableTiers();
                String endPoint = api.getEndpointConfig();
                String apiSecurity = api.getApiSecurity();
                boolean isOauthProtected =
                        apiSecurity == null || apiSecurity.contains(APIConstants.DEFAULT_API_SECURITY_OAUTH2);
                if (endPoint != null && endPoint.trim().length() > 0) {
                    if (isOauthProtected && (tiers == null || tiers.size() <= 0)) {
                        throw new APIManagementException("Failed to publish service to API store while executing " +
                                "APIExecutor. No Tiers selected");
                    }
                } else {
                    throw new APIManagementException("Failed to publish service to API store while executing"
                            + " APIExecutor. No endpoint selected");
                }
            }

            //push the state change to gateway
            Map<String, String> failedGateways = apiProvider
                    .propergateAPIStatusChangeToGateways(api, api.getId(), newStatus);

            if (log.isDebugEnabled()) {
                String logMessage = "Publish changed status to the Gateway. API Name: " + api.getId().getApiName()
                        + ", API Version " + api.getId().getVersion() + ", API Context: " + api.getContext()
                        + ", New Status : " + newStatus;
                log.debug(logMessage);
            }

            // set api status to old as propergateAPIStatusChangeToGateways() method call will change api status
            // to newStatus and this modified api is sent back to updateAPIforStateChange() method
            api.setStatus(oldStatus);
            //update api related information for state change
            executed = apiProvider
                    .updateAPIforStateChange(api, api.getId(), newStatus, failedGateways, artifactManager, apiArtifact);

            // Setting resource again to the context as it's updated within updateAPIStatus method
            String apiPath = APIUtil.getAPIPath(api.getId());

            apiResource = registry.get(apiPath);
            context.setResource(apiResource);

            if (log.isDebugEnabled()) {
                String logMessage =
                        "API related information successfully updated. API Name: " + api.getId().getApiName()
                                + ", API Version " + api.getId().getVersion() + ", API Context: " + api.getContext()
                                + ", New Status : " + newStatus;
                log.debug(logMessage);
            }
        } else {
            throw new APIManagementException("Invalid Lifecycle status for default APIExecutor :" + targetState);
        }


        boolean deprecateOldVersions = false;
        boolean makeKeysForwardCompatible = false;
        int deprecateOldVersionsCheckListOrder = 0, makeKeysForwardCompatibleCheckListOrder = 1;
        //If the API status is CREATED/PROTOTYPED ,check for check list items of lifecycle
        if (isCurrentCreatedOrPrototyped) {
            CheckListItemBean[] checkListItemBeans = GovernanceUtils
                    .getAllCheckListItemBeans(apiResource, apiArtifact, APIConstants.API_LIFE_CYCLE);
            if (checkListItemBeans != null) {
                for (CheckListItemBean checkListItemBean : checkListItemBeans) {
                    if (APIConstants.DEPRECATE_CHECK_LIST_ITEM.equals(checkListItemBean.getName())) {
                        deprecateOldVersionsCheckListOrder = checkListItemBean.getOrder();
                    } else if (APIConstants.RESUBSCRIBE_CHECK_LIST_ITEM.equals(checkListItemBean.getName())) {
                        makeKeysForwardCompatibleCheckListOrder = checkListItemBean.getOrder();
                    }
                }
            }
            deprecateOldVersions = apiArtifact
                    .isLCItemChecked(deprecateOldVersionsCheckListOrder, APIConstants.API_LIFE_CYCLE);
            makeKeysForwardCompatible = !(apiArtifact
                    .isLCItemChecked(makeKeysForwardCompatibleCheckListOrder, APIConstants.API_LIFE_CYCLE));
        }

        if (isStateTransitionToPublished) {
            if (makeKeysForwardCompatible) {
                apiProvider.makeAPIKeysForwardCompatible(api);
            }
            if (deprecateOldVersions) {
                String provider = APIUtil.replaceEmailDomain(api.getId().getProviderName());

                List<API> apiList = apiProvider.getAPIsByProvider(provider);
                APIVersionComparator versionComparator = new APIVersionComparator();
                for (API oldAPI : apiList) {
                    if (oldAPI.getId().getApiName().equals(api.getId().getApiName()) &&
                            versionComparator.compare(oldAPI, api) < 0 &&
                            (APIConstants.PUBLISHED.equals(oldAPI.getStatus()))) {
                        apiProvider.changeLifeCycleStatus(oldAPI.getId(), APIConstants.API_LC_ACTION_DEPRECATE);

                    }
                }
            }
            //Deploy API in selected cloud clusters
            if (api.getDeploymentEnvironments() != null && !api.getDeploymentEnvironments().isEmpty()) {
                apiProvider.publishInPrivateJet(api, api.getId());
            }
        }

        return executed;
    }
}