/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.apimgt.impl.importexport.utils;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIDefinition;
import org.wso2.carbon.apimgt.api.APIDefinitionValidationResponse;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIMgtAuthorizationFailedException;
import org.wso2.carbon.apimgt.api.APIMgtResourceAlreadyExistsException;
import org.wso2.carbon.apimgt.api.APIMgtResourceNotFoundException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.ErrorHandler;
import org.wso2.carbon.apimgt.api.ExceptionCodes;
import org.wso2.carbon.apimgt.api.FaultGatewaysException;
import org.wso2.carbon.apimgt.api.doc.model.APIResource;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIStatus;
import org.wso2.carbon.apimgt.api.model.ApiTypeWrapper;
import org.wso2.carbon.apimgt.api.model.Scope;
import org.wso2.carbon.apimgt.api.model.SwaggerData;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.certificatemgt.ResponseCode;
import org.wso2.carbon.apimgt.impl.definitions.OASParserUtil;
import org.wso2.carbon.apimgt.impl.importexport.APIImportExportConstants;
import org.wso2.carbon.apimgt.impl.importexport.APIImportExportException;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.wsdl.util.SOAPToRESTConstants;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.core.util.CryptoUtil;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class provides the functions utilized to import an API from an API archive.
 */
public final class APIImportUtil {

    private static final Log log = LogFactory.getLog(APIImportUtil.class);
    private static final String IN = "in";
    private static final String OUT = "out";
    private static final String SOAPTOREST = "SoapToRest";

    /**
     * Load the graphQL schema definition from archive.
     *
     * @param pathToArchive Path to archive
     * @return Schema definition content
     * @throws IOException When SDL file not found
     */
    private static String loadGraphqlSDLFile(String pathToArchive) throws IOException {
        if (CommonUtil.checkFileExistence(pathToArchive + APIImportExportConstants.GRAPHQL_SCHEMA_DEFINITION_LOCATION)) {
            if (log.isDebugEnabled()) {
                log.debug("Found graphQL sdl file " + pathToArchive
                        + APIImportExportConstants.GRAPHQL_SCHEMA_DEFINITION_LOCATION);
            }
            return FileUtils.readFileToString(
                    new File(pathToArchive, APIImportExportConstants.GRAPHQL_SCHEMA_DEFINITION_LOCATION));
        }
        throw new IOException("Missing graphQL schema definition file. schema.graphql should be present.");
    }

    /**
     * This method imports an API.
     *
     * @param pathToArchive            location of the extracted folder of the API
     * @param currentUser              the current logged in user
     * @param isDefaultProviderAllowed decision to keep or replace the provider
     * @throws APIImportExportException if there is an error in importing an API
     */
    public static void importAPI(String pathToArchive, String currentUser, boolean isDefaultProviderAllowed,
            APIProvider apiProvider, Boolean overwrite)
            throws APIImportExportException {

        String jsonContent = null;
        API importedApi = null;
        API targetApi = null; //target API when overwrite is
        ApiTypeWrapper apiTypeWrapper;
        String prevProvider;
        String apiName;
        String apiVersion;
        String currentTenantDomain;
        String currentStatus;
        String targetStatus;
        // Map to store the target life cycle state as key and life cycle action as the value
        Map<String, String> lifecycleActions = new LinkedHashMap<>();
        String swaggerContent = null;
        UserRegistry registry;
        int tenantId = APIUtil.getTenantId(currentUser);

        try {
            registry = ServiceReferenceHolder.getInstance().getRegistryService().getGovernanceSystemRegistry(tenantId);

            // Get API Definition as JSON
            jsonContent = APIAndAPIProductCommonUtil.getAPIDefinitionAsJson(pathToArchive);
            if (jsonContent == null) {
                throw new IOException("Cannot find API definition. api.json or api.yaml should present");
            }
            JsonElement configElement = new JsonParser().parse(jsonContent);
            JsonObject configObject = configElement.getAsJsonObject();

            //locate the "providerName" within the "id" and set it as the current user
            JsonObject apiId = configObject.getAsJsonObject(APIImportExportConstants.ID_ELEMENT);

            prevProvider = apiId.get(APIImportExportConstants.PROVIDER_ELEMENT).getAsString();
            apiName = apiId.get(APIImportExportConstants.API_NAME_ELEMENT).getAsString();
            apiVersion = apiId.get(APIImportExportConstants.VERSION_ELEMENT).getAsString();

            // Remove spaces of API Name/version if present
            if (apiName != null && apiVersion != null) {
                apiId.addProperty(APIImportExportConstants.API_NAME_ELEMENT,
                        apiName = apiName.replace(" ", ""));
                apiId.addProperty(APIImportExportConstants.VERSION_ELEMENT,
                        apiVersion = apiVersion.replace(" ", ""));
            } else {
                throw new IOException("API Name (id.apiName) and Version (id.version) must be provided in api.yaml");
            }

            String prevTenantDomain = MultitenantUtils
                    .getTenantDomain(APIUtil.replaceEmailDomainBack(prevProvider));
            currentTenantDomain = MultitenantUtils
                    .getTenantDomain(APIUtil.replaceEmailDomainBack(currentUser));

            // If the original provider is preserved,
            if (isDefaultProviderAllowed) {
                if (!StringUtils.equals(prevTenantDomain, currentTenantDomain)) {
                    String errorMessage = "Tenant mismatch! Please enable preserveProvider property "
                            + "for cross tenant API Import.";
                    throw new APIMgtAuthorizationFailedException(errorMessage);
                }
                importedApi = new Gson().fromJson(configElement, API.class);
            } else {
                String prevProviderWithDomain = APIUtil.replaceEmailDomain(prevProvider);
                String currentUserWithDomain = APIUtil.replaceEmailDomain(currentUser);
                apiId.addProperty(APIImportExportConstants.PROVIDER_ELEMENT, currentUserWithDomain);
                if (configObject.get(APIImportExportConstants.WSDL_URL) != null) {
                    // If original provider is not preserved, replace provider name in the wsdl URL
                    // with the current user with domain name
                    configObject.addProperty(APIImportExportConstants.WSDL_URL,
                            configObject.get(APIImportExportConstants.WSDL_URL).getAsString()
                                    .replace(prevProviderWithDomain, currentUserWithDomain));
                }

                importedApi = new Gson().fromJson(configElement, API.class);
                //Replace context to match with current provider
                apiTypeWrapper = new ApiTypeWrapper(importedApi);
                APIAndAPIProductCommonUtil.setCurrentProviderToAPIProperties(apiTypeWrapper, currentTenantDomain, prevTenantDomain);
            }

            // Validate API context
            if (importedApi.getId() != null) {
                APIUtil.validateAPIContext(importedApi.getContext(), importedApi.getId().getApiName());
            }

            // Validate the swagger definition if the API type is not Web Socket.
            if (!APIConstants.APITransportType.WS.toString().equalsIgnoreCase(importedApi.getType())) {
                swaggerContent = APIAndAPIProductCommonUtil.loadSwaggerFile(pathToArchive);
                APIDefinitionValidationResponse response = OASParserUtil.validateAPIDefinition(swaggerContent, true);
                if (!response.isValid()) {
                    String errorDescription = ExceptionCodes.OPENAPI_PARSE_EXCEPTION.getErrorMessage();
                    if (response.getErrorItems().size() > 0) {
                        for (ErrorHandler errorHandler : response.getErrorItems()) {
                            errorDescription = errorDescription.concat(". " + errorHandler.getErrorDescription());
                        }
                    }
                    throw new APIImportExportException(errorDescription);
                }
            }

            handleEndpointSecurityConfigs(importedApi);

            // Store imported API status
            targetStatus = importedApi.getStatus();
            if (Boolean.TRUE.equals(overwrite)) {
                String provider = APIUtil
                        .getAPIProviderFromAPINameVersionTenant(apiName, apiVersion, currentTenantDomain);
                APIIdentifier apiIdentifier = new APIIdentifier(APIUtil.replaceEmailDomain(provider), apiName,
                        apiVersion);
                // Checking whether the API exists
                if (!apiProvider.isAPIAvailable(apiIdentifier)) {
                    String errorMessage = "Error occurred while updating. API: " + apiName + StringUtils.SPACE
                            + APIConstants.API_DATA_VERSION + ": " + apiVersion + " not found";
                    throw new APIMgtResourceNotFoundException(errorMessage);
                }
                targetApi = apiProvider.getAPI(apiIdentifier);
                // Store target API status
                currentStatus = targetApi.getStatus();

                // Since the overwrite should be done, the imported API Identifier should be equal to the target API Identifier
                importedApi.setId(targetApi.getId());
                // Set the UUID of the imported API from the targetAPI.
                importedApi.setUUID(targetApi.getUUID());
            } else {
                if (apiProvider.isAPIAvailable(importedApi.getId())
                        || apiProvider.isApiNameWithDifferentCaseExist(apiName)) {
                    String errorMessage = "Error occurred while adding the API. A duplicate API already exists " +
                            "for " + importedApi.getId().getApiName() + '-' + importedApi.getId().getVersion();
                    throw new APIMgtResourceAlreadyExistsException(errorMessage);
                }

                if (apiProvider.isContextExist(importedApi.getContext())) {
                    String errMsg = "Error occurred while adding the API [" + importedApi.getId().getApiName()
                            + '-' + importedApi.getId().getVersion() + "]. A duplicate context["
                            + importedApi.getContext() + "] already exists";
                    throw new APIMgtResourceAlreadyExistsException(errMsg);
                }

                if (!apiProvider.isValidContext(importedApi.getId().getProviderName(), importedApi.getId().getApiName(),
                                                importedApi.getContextTemplate(), currentUser)) {
                    String errMsg = "Error occurred while adding the API [" + importedApi.getId().getApiName() + '-'
                            + importedApi.getId().getVersion()
                            + "]. Context doesn't match with the previous version(s).";
                    throw new IOException(errMsg);
                }

                // Initialize to CREATED when import
                currentStatus = APIStatus.CREATED.toString();
            }
            //set the status of imported API to CREATED (importing API) or current status of target API when updating
            importedApi.setStatus(currentStatus);


            if (importedApi.isDefaultVersion()) {
                if (!importedApi.getStatus().equalsIgnoreCase(APIStatus.PUBLISHED.toString())) {
                    importedApi.setAsPublishedDefaultVersion(false);
                } else {
                    importedApi.setAsPublishedDefaultVersion(true);
                }
            }

            if (!currentStatus.equals(targetStatus)) {
                lifecycleActions = retrieveLifeCycleActions(currentTenantDomain, currentStatus, targetStatus,
                        apiProvider);
            }

            Set<Tier> allowedTiers;
            Set<Tier> unsupportedTiersList;
            allowedTiers = apiProvider.getTiers();

            if (!(allowedTiers.isEmpty())) {
                unsupportedTiersList = Sets.difference(importedApi.getAvailableTiers(), allowedTiers);

                //If at least one unsupported tier is found, it should be removed before adding API
                if (!(unsupportedTiersList.isEmpty())) {
                    //Process is continued with a warning and only supported tiers are added to the importer API
                    unsupportedTiersList.forEach(unsupportedTier ->
                            log.warn("Tier name : " + unsupportedTier.getName() + " is not supported."));
                    //Remove the unsupported tiers before adding the API
                    importedApi.removeAvailableTiers(unsupportedTiersList);
                }
            }
            if (Boolean.FALSE.equals(overwrite)) {
                //Add API in CREATED state
                apiProvider.addAPI(importedApi);
            }

            //Swagger definition will only be available of API type HTTP. Web socket API does not have it.
            if (!APIConstants.APITransportType.WS.toString().equalsIgnoreCase(importedApi.getType())
                    && !StringUtils.isBlank(swaggerContent)) {
                //preProcess swagger definition
                swaggerContent = OASParserUtil.preProcess(swaggerContent);

                // Check whether any of the resources should be removed from the API when updating,
                // that has already been used in API Products
                List<APIResource> resourcesToRemove = apiProvider.getResourcesToBeRemovedFromAPIProducts(importedApi.getId(),
                        swaggerContent);
                // Do not allow to remove resources from API Products, hence throw an exception
                if (!resourcesToRemove.isEmpty()) {
                    throw new APIImportExportException("Cannot remove following resource paths " +
                            resourcesToRemove.toString() + " because they are used by one or more API Products");
                }

                addSwaggerDefinition(importedApi.getId(), swaggerContent, apiProvider);
                APIDefinition apiDefinition = OASParserUtil.getOASParser(swaggerContent);
                //If graphQL API, import graphQL schema definition to registry
                if (StringUtils.equals(importedApi.getType(), APIConstants.APITransportType.GRAPHQL.toString())) {
                    String schemaDefinition = loadGraphqlSDLFile(pathToArchive);
                    addGraphqlSchemaDefinition(importedApi, schemaDefinition, apiProvider);
                } else {
                    //Load required properties from swagger to the API
                    Set<URITemplate> uriTemplates = apiDefinition.getURITemplates(swaggerContent);
                    String defaultAPILevelPolicy = APIUtil.getDefaultAPILevelPolicy(tenantId);
                    for (URITemplate uriTemplate : uriTemplates) {
                        Scope scope = uriTemplate.getScope();
                        if (scope != null && !(APIUtil.isAllowedScope(scope.getKey())) &&
                                apiProvider.isScopeKeyAssignedLocally(importedApi.getId(), scope.getKey(), tenantId)) {
                            String errorMessage =
                                    "Error in adding API. Scope " + scope.getKey() +
                                            " is already assigned by another API.";
                            throw new APIImportExportException(errorMessage);
                        }
                        if (StringUtils.isEmpty(uriTemplate.getThrottlingTier())) {
                            uriTemplate.setThrottlingTier(defaultAPILevelPolicy);
                        }
                    }
                    importedApi.setUriTemplates(uriTemplates);
                    Set<Scope> scopes = apiDefinition.getScopes(swaggerContent);
                    importedApi.setScopes(scopes);
                    //Set extensions from API definition to API object
                    importedApi = OASParserUtil.setExtensionsToAPI(swaggerContent, importedApi);
                }

                // Generate API definition using the given API's URI templates and the swagger
                // (Adding missing attributes to swagger)
                SwaggerData swaggerData = new SwaggerData(importedApi);
                String newDefinition = apiDefinition.generateAPIDefinition(swaggerData, swaggerContent);
                apiProvider.saveSwaggerDefinition(importedApi, newDefinition);
            }

            //Since Image, documents, sequences and WSDL are optional, exceptions are logged and ignored in implementation
            ApiTypeWrapper apiTypeWrapperWithUpdatedApi = new ApiTypeWrapper(importedApi);
            APIAndAPIProductCommonUtil.addAPIOrAPIProductImage(pathToArchive, apiTypeWrapperWithUpdatedApi, apiProvider);
            APIAndAPIProductCommonUtil.addAPIOrAPIProductDocuments(pathToArchive, apiTypeWrapperWithUpdatedApi, apiProvider);
            addAPISequences(pathToArchive, importedApi, registry);
            addAPISpecificSequences(pathToArchive, importedApi, registry);
            addAPIWsdl(pathToArchive, importedApi, apiProvider, registry);
            addEndpointCertificates(pathToArchive, importedApi, apiProvider, tenantId);
            addSOAPToREST(pathToArchive, importedApi, registry);

            if (apiProvider.isClientCertificateBasedAuthenticationConfigured()) {
                if (log.isDebugEnabled()) {
                    log.debug("Mutual SSL enabled. Importing client certificates.");
                }
                APIAndAPIProductCommonUtil.addClientCertificates(pathToArchive, apiProvider);
            }

            // This is required to make url templates and scopes get effected
            apiProvider.updateAPI(importedApi);

            // Change the API life cycle if state transition is required
            if (!currentStatus.equals(targetStatus)) {
                changeLifeCycleStatus(lifecycleActions, currentStatus, importedApi, apiProvider);
            }
        } catch (IOException e) {
            //Error is logged and APIImportExportException is thrown because adding API and swagger are mandatory steps
            String errorMessage = "Error while reading API meta information from path: " + pathToArchive;
            throw new APIImportExportException(errorMessage, e);
        } catch (FaultGatewaysException e) {
            String errorMessage = "Error while updating API: " + importedApi.getId().getApiName();
            throw new APIImportExportException(errorMessage, e);
        } catch (RegistryException e) {
            String errorMessage = "Error while getting governance registry for tenant: " + tenantId;
            throw new APIImportExportException(errorMessage, e);
        } catch (CryptoException e) {
            String errorMessage =
                    "Error while encrypting the secret key of API : " + importedApi.getId().getProviderName() + "-"
                            + importedApi.getId().getApiName() + "-" + importedApi.getId().getVersion() + " - "
                            + e.getMessage();
            throw new APIImportExportException(errorMessage, e);
        } catch (APIManagementException e) {
            String errorMessage = "Error while importing API: ";
            if (e.getMessage().contains("The API context cannot be a malformed one")) {
                errorMessage += importedApi.getId().getApiName() + " with context " + importedApi.getContext() + ": "
                        + e.getMessage();
                throw new APIImportExportException(errorMessage, e);
            }
            if (importedApi != null) {
                errorMessage += importedApi.getId().getApiName() + StringUtils.SPACE + APIConstants.API_DATA_VERSION
                        + ": " + importedApi.getId().getVersion();
            }
            throw new APIImportExportException(errorMessage, e);
        }
    }

    /**
     * This method retrieves the life cycle actions
     *
     * @param tenantDomain  tenant domain
     * @param currentStatus Current lifecycle status
     * @param targetStatus  Target lifecycle status
     * @param apiProvider   API Provider
     * @return Life Cycle actions
     * @throws APIImportExportException if the target lifecycle state is not reachable from the current state
     */
    private static Map<String, String> retrieveLifeCycleActions(String tenantDomain, String currentStatus,
            String targetStatus, APIProvider apiProvider) throws APIImportExportException {
        Map<String, String> lifeCycleActions = new LinkedHashMap<>();
        if (StringUtils.equals(targetStatus, APIStatus.BLOCKED.toString()) || StringUtils.equals(targetStatus,
                APIStatus.DEPRECATED.toString()) || StringUtils.equals(targetStatus, APIStatus.RETIRED.toString())) {
            if (StringUtils.equals(currentStatus, APIStatus.CREATED.toString())) {
                lifeCycleActions.put(APIStatus.PUBLISHED.toString(),
                        retrieveLifeCycleAction(tenantDomain, currentStatus, APIStatus.PUBLISHED.toString(), apiProvider));
                currentStatus = APIStatus.PUBLISHED.toString();
            }
            if (StringUtils.equals(targetStatus, APIStatus.RETIRED.toString())) {
                // The API should be Deprecated prior Retiring the API
                lifeCycleActions.put(APIStatus.DEPRECATED.toString(),
                        retrieveLifeCycleAction(tenantDomain, currentStatus, APIStatus.DEPRECATED.toString(),
                                apiProvider));
                currentStatus = APIStatus.DEPRECATED.toString();
            }
        }
        lifeCycleActions.put(targetStatus,
                retrieveLifeCycleAction(tenantDomain, currentStatus, targetStatus, apiProvider));
        return lifeCycleActions;
    }

    /**
     * @param tenantDomain  Tenant Domain
     * @param currentStatus Current life cycle status
     * @param targetStatus  Target life cycle status
     * @param apiProvider   API Provider
     * @return Life cycle action
     * @throws APIImportExportException if the target life cycle status is not reachable from the current life cycle
     *                                  status
     */
    private static String retrieveLifeCycleAction(String tenantDomain, String currentStatus, String targetStatus,
            APIProvider apiProvider) throws APIImportExportException {
        String lifecycleAction = APIAndAPIProductCommonUtil.getLifeCycleAction(tenantDomain, currentStatus,
                targetStatus, apiProvider);
        if (lifecycleAction == null) {
            String errMsg =
                    "Error occurred while importing the API. " + targetStatus + " is not reachable from "
                            + currentStatus;
            throw new APIImportExportException(errMsg);
        }
        return lifecycleAction;
    }

    /**
     * This method changes the lifecycle status of an API
     *
     * @param lifecycleActions Life cycle actions map
     * @param currentStatus    Current lifecycle status
     * @param importedApi      Imported API
     * @param apiProvider      API Provider
     * @throws APIManagementException if an error occurs while changing the lifecycle status
     * @throws FaultGatewaysException if an error occurs while changing the lifecycle status
     */
    private static void changeLifeCycleStatus(Map<String, String> lifecycleActions, String currentStatus,
            API importedApi, APIProvider apiProvider) throws APIManagementException, FaultGatewaysException {
        if (!lifecycleActions.isEmpty()) {
            for (Map.Entry<String, String> lifeCycleAction : lifecycleActions.entrySet()) {
                // Change API the life cycle if the state transition is required
                if (StringUtils.isNotEmpty(lifeCycleAction.getValue())) {
                    log.info("Changing lifecycle from " + currentStatus + " to " + lifeCycleAction.getKey());
                    if (StringUtils.equals(lifeCycleAction.getValue(), APIConstants.LC_PUBLISH_LC_STATE)) {
                        apiProvider.changeAPILCCheckListItems(importedApi.getId(),
                                APIImportExportConstants.REFER_REQUIRE_RE_SUBSCRIPTION_CHECK_ITEM, true);
                    }
                    apiProvider.changeLifeCycleStatus(importedApi.getId(), lifeCycleAction.getValue());
                    currentStatus = lifeCycleAction.getKey();
                }
            }
        }
    }

    /**
     * This method gives priority to the endpoint security configurations defined using the params file and
     * override the endpointConfig if needed
     *
     * @param importedApi the imported API object
     */
    private static void handleEndpointSecurityConfigs(API importedApi)
            throws CryptoException, APIImportExportException {
        Boolean isEndpointSecured = importedApi.isEndpointSecured();
        String endpointUsername = importedApi.getEndpointUTUsername();
        String endpointPassword = importedApi.getEndpointUTPassword();
        String endpointSecurityType = (importedApi.isEndpointAuthDigest() ?
                APIConstants.ENDPOINT_SECURITY_TYPE_DIGEST :
                APIConstants.ENDPOINT_SECURITY_TYPE_BASIC).toUpperCase();
        String[] endpointTypes = { APIConstants.ENDPOINT_SECURITY_PRODUCTION, APIConstants.ENDPOINT_SECURITY_SANDBOX };

        String endpointConfig = importedApi.getEndpointConfig();
        if (StringUtils.isNotBlank(endpointConfig)) {
            JsonObject endpointConfigObject = new JsonParser().parse(endpointConfig).getAsJsonObject();
            if (isEndpointSecured && StringUtils.isNotBlank(endpointUsername) && StringUtils.isNotBlank(
                    endpointPassword)) {
                // Remove existing endpoint security in order to override from the params file related properties
                if (endpointConfigObject.has(APIConstants.ENDPOINT_SECURITY)) {
                    endpointConfigObject.remove(APIConstants.ENDPOINT_SECURITY);
                }

                JsonObject endpointSecurityConfig = new JsonObject();
                for (String endpointType : endpointTypes) {
                    JsonObject endpointSecurityForEndpointType = new JsonObject();
                    endpointSecurityForEndpointType.addProperty(APIConstants.ENDPOINT_SECURITY_ENABLED, Boolean.TRUE);
                    endpointSecurityForEndpointType.addProperty(APIConstants.ENDPOINT_SECURITY_TYPE,
                            endpointSecurityType);
                    endpointSecurityForEndpointType.addProperty(APIConstants.ENDPOINT_SECURITY_USERNAME,
                            endpointUsername);
                    endpointSecurityForEndpointType.addProperty(APIConstants.ENDPOINT_SECURITY_PASSWORD,
                            endpointPassword);
                    endpointSecurityForEndpointType.addProperty(APIConstants.OAuthConstants.OAUTH_CUSTOM_PARAMETERS,
                            new JsonObject().toString());
                    endpointSecurityConfig.add(endpointType, endpointSecurityForEndpointType);
                }

                endpointConfigObject.add(APIConstants.ENDPOINT_SECURITY, endpointSecurityConfig);
                importedApi.setEndpointConfig(endpointConfigObject.toString());
            }

            // For OAuth 2.0 support using the params file of apictl
            if (endpointConfigObject.has(APIConstants.ENDPOINT_SECURITY) && !endpointConfigObject.get(
                    APIConstants.ENDPOINT_SECURITY).isJsonNull()) {
                CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
                JsonObject endpointSecurityObject = endpointConfigObject.get(APIConstants.ENDPOINT_SECURITY)
                        .getAsJsonObject();
                for (String endpointType : endpointTypes) {
                    if (endpointSecurityObject.has(endpointType) && !endpointSecurityObject.get(endpointType)
                            .isJsonNull()) {
                        JsonObject endpointSecurityConfigPerType = endpointSecurityObject.get(endpointType)
                                .getAsJsonObject();

                        if (endpointSecurityConfigPerType.has(APIConstants.OAuthConstants.GRANT_TYPE)
                                && !endpointSecurityConfigPerType.get(
                                APIConstants.OAuthConstants.GRANT_TYPE).isJsonNull()) {
                            if (StringUtils.equals(endpointSecurityConfigPerType.get(
                                            APIConstants.OAuthConstants.GRANT_TYPE).getAsString().toLowerCase(),
                                    APIConstants.OAuthConstants.PASSWORD.toLowerCase())) {
                                if (!endpointSecurityConfigPerType.has(
                                        APIConstants.OAuthConstants.ENDPOINT_SECURITY_PASSWORD)
                                        || endpointSecurityConfigPerType.get(
                                        APIConstants.OAuthConstants.ENDPOINT_SECURITY_PASSWORD).isJsonNull()) {
                                    throw new APIImportExportException(
                                            "Password cannot be empty/null when " + APIConstants.OAuthConstants.PASSWORD
                                                    + " grant type is selected for " + "endpoint security");
                                }
                            }
                        }

                        // The client secret should be encrypted only if the isSecretEncrypted property is set inside
                        // endpoint security config and only if it has the value as false
                        // (This isSecurityEncrypted: false will only be set when the user is using a params file to
                        // override OAuth 2.0 endpoint security)
                        if (endpointSecurityConfigPerType.has(APIImportExportConstants.IS_SECRET_ENCRYPTED)
                                && !endpointSecurityConfigPerType.get(APIImportExportConstants.IS_SECRET_ENCRYPTED)
                                .isJsonNull()) {
                            boolean isSecretEncrypted = endpointSecurityConfigPerType.get(
                                    APIImportExportConstants.IS_SECRET_ENCRYPTED).getAsBoolean();
                            if (!isSecretEncrypted) {
                                if (endpointSecurityConfigPerType.has(
                                        APIConstants.OAuthConstants.OAUTH_CLIENT_SECRET)) {
                                    String encryptedClientSecret = cryptoUtil.encryptAndBase64Encode(
                                            endpointSecurityConfigPerType.get(
                                                    APIConstants.OAuthConstants.OAUTH_CLIENT_SECRET).getAsString()
                                                    .getBytes());
                                    endpointSecurityConfigPerType.addProperty(
                                            APIConstants.OAuthConstants.OAUTH_CLIENT_SECRET, encryptedClientSecret);
                                }
                                // The property isSecurityEncrypted will be removed from the endpointConfig because
                                // it is used only in this apictl use case
                                endpointSecurityConfigPerType.remove(APIImportExportConstants.IS_SECRET_ENCRYPTED);

                                // Convert customParameters to String since this is the params flow
                                if (endpointSecurityConfigPerType.has(
                                        APIConstants.OAuthConstants.OAUTH_CUSTOM_PARAMETERS)
                                        && !endpointSecurityConfigPerType.get(
                                        APIConstants.OAuthConstants.OAUTH_CUSTOM_PARAMETERS).isJsonNull()) {
                                    String customParametersString = endpointSecurityConfigPerType.get(
                                            APIConstants.OAuthConstants.OAUTH_CUSTOM_PARAMETERS).getAsJsonObject()
                                            .toString();
                                    endpointSecurityConfigPerType.addProperty(
                                            APIConstants.OAuthConstants.OAUTH_CUSTOM_PARAMETERS,
                                            customParametersString);
                                } else {
                                    endpointSecurityConfigPerType.addProperty(
                                            APIConstants.OAuthConstants.OAUTH_CUSTOM_PARAMETERS, "{}");
                                }
                            }
                        }
                    }
                }
                importedApi.setEndpointConfig(endpointConfigObject.toString());
            }
        }
    }

    /**
     * This method adds API sequences to the imported API. If the sequence is a newly defined one, it is added.
     *
     * @param pathToArchive location of the extracted folder of the API
     * @param importedApi   the imported API object
     */
    private static void addAPISequences(String pathToArchive, API importedApi, Registry registry) {

        String inSequenceFileName = importedApi.getInSequence() + APIConstants.XML_EXTENSION;
        String inSequenceFileLocation = pathToArchive + APIImportExportConstants.IN_SEQUENCE_LOCATION
                + inSequenceFileName;
        String regResourcePath;

        //Adding in-sequence, if any
        if (CommonUtil.checkFileExistence(inSequenceFileLocation)) {
            regResourcePath = APIConstants.API_CUSTOM_INSEQUENCE_LOCATION + inSequenceFileName;
            addSequenceToRegistry(false, registry, inSequenceFileLocation, regResourcePath);
        }

        String outSequenceFileName = importedApi.getOutSequence() + APIConstants.XML_EXTENSION;
        String outSequenceFileLocation = pathToArchive + APIImportExportConstants.OUT_SEQUENCE_LOCATION
                + outSequenceFileName;

        //Adding out-sequence, if any
        if (CommonUtil.checkFileExistence(outSequenceFileLocation)) {
            regResourcePath = APIConstants.API_CUSTOM_OUTSEQUENCE_LOCATION + outSequenceFileName;
            addSequenceToRegistry(false, registry, outSequenceFileLocation, regResourcePath);
        }

        String faultSequenceFileName = importedApi.getFaultSequence() + APIConstants.XML_EXTENSION;
        String faultSequenceFileLocation = pathToArchive + APIImportExportConstants.FAULT_SEQUENCE_LOCATION
                + faultSequenceFileName;

        //Adding fault-sequence, if any
        if (CommonUtil.checkFileExistence(faultSequenceFileLocation)) {
            regResourcePath = APIConstants.API_CUSTOM_FAULTSEQUENCE_LOCATION + faultSequenceFileName;
            addSequenceToRegistry(false, registry, faultSequenceFileLocation, regResourcePath);
        }
    }

    /**
     * This method adds API Specific sequences added through the Publisher to the imported API. If the specific
     * sequence already exists, it is updated.
     *
     * @param pathToArchive location of the extracted folder of the API
     * @param importedApi   the imported API object
     */
    private static void addAPISpecificSequences(String pathToArchive, API importedApi, Registry registry) {

        String regResourcePath = APIConstants.API_ROOT_LOCATION + RegistryConstants.PATH_SEPARATOR
                + importedApi.getId().getProviderName() + RegistryConstants.PATH_SEPARATOR
                + importedApi.getId().getApiName() + RegistryConstants.PATH_SEPARATOR
                + importedApi.getId().getVersion() + RegistryConstants.PATH_SEPARATOR;

        String inSequenceFileName = importedApi.getInSequence();
        String inSequenceFileLocation = pathToArchive + APIImportExportConstants.IN_SEQUENCE_LOCATION
                + APIImportExportConstants.CUSTOM_TYPE + File.separator + inSequenceFileName;

        //Adding in-sequence, if any
        if (CommonUtil.checkFileExistence(inSequenceFileLocation + APIConstants.XML_EXTENSION)) {
            String inSequencePath = APIConstants.API_CUSTOM_SEQUENCE_TYPE_IN + RegistryConstants.PATH_SEPARATOR
                    + inSequenceFileName;
            addSequenceToRegistry(true, registry, inSequenceFileLocation + APIConstants.XML_EXTENSION, regResourcePath + inSequencePath);
        }

        String outSequenceFileName = importedApi.getOutSequence() + APIConstants.XML_EXTENSION;
        String outSequenceFileLocation = pathToArchive + APIImportExportConstants.OUT_SEQUENCE_LOCATION
                + APIImportExportConstants.CUSTOM_TYPE + File.separator + outSequenceFileName;

        //Adding out-sequence, if any
        if (CommonUtil.checkFileExistence(outSequenceFileLocation)) {
            String outSequencePath = APIConstants.API_CUSTOM_SEQUENCE_TYPE_OUT + RegistryConstants.PATH_SEPARATOR
                    + outSequenceFileName;
            addSequenceToRegistry(true, registry, outSequenceFileLocation, regResourcePath + outSequencePath);
        }

        String faultSequenceFileName = importedApi.getFaultSequence() + APIConstants.XML_EXTENSION;
        String faultSequenceFileLocation = pathToArchive + APIImportExportConstants.FAULT_SEQUENCE_LOCATION
                + APIImportExportConstants.CUSTOM_TYPE + File.separator + faultSequenceFileName;

        //Adding fault-sequence, if any
        if (CommonUtil.checkFileExistence(faultSequenceFileLocation)) {
            String faultSequencePath = APIConstants.API_CUSTOM_SEQUENCE_TYPE_FAULT + RegistryConstants.PATH_SEPARATOR
                    + faultSequenceFileName;
            addSequenceToRegistry(true, registry, faultSequenceFileLocation, regResourcePath + faultSequencePath);
        }
    }

    /**
     * This method adds the sequence files to the registry. This updates the API specific sequences if already exists.
     *
     * @param isAPISpecific        whether the adding sequence is API specific
     * @param registry             the registry instance
     * @param sequenceFileLocation location of the sequence file
     */
    private static void addSequenceToRegistry(Boolean isAPISpecific, Registry registry, String sequenceFileLocation,
            String regResourcePath) {

        try {
            if (registry.resourceExists(regResourcePath) && !isAPISpecific) {
                if (log.isDebugEnabled()) {
                    log.debug("Sequence already exists in registry path: " + regResourcePath);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Adding Sequence to the registry path : " + regResourcePath);
                }
                File sequenceFile = new File(sequenceFileLocation);
                try (InputStream seqStream = new FileInputStream(sequenceFile);) {
                    byte[] inSeqData = IOUtils.toByteArray(seqStream);
                    Resource inSeqResource = registry.newResource();
                    inSeqResource.setContent(inSeqData);
                    registry.put(regResourcePath, inSeqResource);
                }
            }
        } catch (RegistryException e) {
            //this is logged and ignored because sequences are optional
            log.error("Failed to add sequences into the registry : " + regResourcePath, e);
        } catch (IOException e) {
            //this is logged and ignored because sequences are optional
            log.error("I/O error while writing sequence data to the registry : " + regResourcePath, e);
        }
    }

    /**
     * This method adds the WSDL to the registry, if there is a WSDL associated with the API.
     *
     * @param pathToArchive location of the extracted folder of the API
     * @param importedApi   the imported API object
     */
    private static void addAPIWsdl(String pathToArchive, API importedApi, APIProvider apiProvider, Registry registry) {

        String wsdlFileName = importedApi.getId().getApiName() + "-" + importedApi.getId().getVersion()
                + APIConstants.WSDL_FILE_EXTENSION;
        String wsdlPath = pathToArchive + APIImportExportConstants.WSDL_LOCATION + wsdlFileName;

        if (CommonUtil.checkFileExistence(wsdlPath)) {
            try {
                URL wsdlFileUrl = new File(wsdlPath).toURI().toURL();
                importedApi.setWsdlUrl(wsdlFileUrl.toString());
                APIUtil.createWSDL(registry, importedApi);
            } catch (MalformedURLException e) {
                //this exception is logged and ignored since WSDL is optional for an API
                log.error("Error in getting WSDL URL. ", e);
            } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
                //this exception is logged and ignored since WSDL is optional for an API
                log.error("Error in putting the WSDL resource to registry. ", e);
            } catch (APIManagementException e) {
                //this exception is logged and ignored since WSDL is optional for an API
                log.error("Error in creating the WSDL resource in the registry. ", e);
            }
        }
    }

    /**
     * This method adds Swagger API definition to registry.
     *
     * @param apiId          Identifier of the imported API
     * @param swaggerContent Content of Swagger file
     * @throws APIImportExportException if there is an error occurs when adding Swagger definition
     */
    private static void addSwaggerDefinition(APIIdentifier apiId, String swaggerContent, APIProvider apiProvider)
            throws APIImportExportException {

        try {
            apiProvider.saveSwagger20Definition(apiId, swaggerContent);
        } catch (APIManagementException e) {
            String errorMessage = "Error in adding Swagger definition for the API: " + apiId.getApiName()
                    + StringUtils.SPACE + APIConstants.API_DATA_VERSION + ": " + apiId.getVersion();
            throw new APIImportExportException(errorMessage, e);
        }
    }

    /**
     * This method adds GraphQL schema definition to the registry.
     *
     * @param api              API to import
     * @param schemaDefinition Content of schema definition
     * @param apiProvider      API Provider
     * @throws APIManagementException if there is an error occurs when adding schema definition
     */
    private static void addGraphqlSchemaDefinition(API api, String schemaDefinition, APIProvider apiProvider)
            throws APIManagementException {
        apiProvider.saveGraphqlSchemaDefinition(api, schemaDefinition);
    }

    /**
     * This method import endpoint certificate.
     *
     * @param pathToArchive location of the extracted folder of the API
     * @param importedApi   the imported API object
     * @throws APIImportExportException If an error occurs while importing endpoint certificates from file
     */
    private static void addEndpointCertificates(String pathToArchive, API importedApi, APIProvider apiProvider,
                                                int tenantId)
            throws APIImportExportException {

        String jsonContent = null;
        String pathToYamlFile = pathToArchive + APIImportExportConstants.YAML_ENDPOINTS_CERTIFICATE_FILE;
        String pathToJsonFile = pathToArchive + APIImportExportConstants.JSON_ENDPOINTS_CERTIFICATE_FILE;

        try {
            // try loading file as YAML
            if (CommonUtil.checkFileExistence(pathToYamlFile)) {
                if (log.isDebugEnabled()) {
                    log.debug("Found certificate file " + pathToYamlFile);
                }
                String yamlContent = FileUtils.readFileToString(new File(pathToYamlFile));
                jsonContent = CommonUtil.yamlToJson(yamlContent);
            } else if (CommonUtil.checkFileExistence(pathToJsonFile)) {
                // load as a json fallback
                if (log.isDebugEnabled()) {
                    log.debug("Found certificate file " + pathToJsonFile);
                }
                jsonContent = FileUtils.readFileToString(new File(pathToJsonFile));
            }
            if (jsonContent == null) {
                log.debug("No certificate file found to be added, skipping certificate import.");
                return;
            }
            JsonElement configElement = new JsonParser().parse(jsonContent);
            JsonArray certificates = configElement.getAsJsonArray().getAsJsonArray();
            certificates.forEach(certificate -> updateAPIWithCertificate(certificate, apiProvider, importedApi,
                    tenantId));
        } catch (IOException e) {
            String errorMessage = "Error in reading " + APIImportExportConstants.YAML_ENDPOINTS_CERTIFICATE_FILE
                    + " file";
            throw new APIImportExportException(errorMessage, e);
        }
    }

    /**
     * Update API with the certificate.
     * If certificate alias already exists for tenant in database, certificate content will be
     * updated in trust store. If cert alias does not exits in database for that tenant, add the certificate to
     * publisher and gateway nodes. In such case if alias already exits in the trust store, update the certificate
     * content for that alias.
     *
     * @param certificate Certificate JSON element
     * @param apiProvider API Provider
     * @param importedApi API to import
     * @param tenantId    Tenant Id
     */
    private static void updateAPIWithCertificate(JsonElement certificate, APIProvider apiProvider, API importedApi,
                                                 int tenantId) {

        String certificateContent = certificate.getAsJsonObject()
                .get(APIImportExportConstants.CERTIFICATE_CONTENT_JSON_KEY).getAsString();
        String alias = certificate.getAsJsonObject().get(APIImportExportConstants.ALIAS_JSON_KEY).getAsString();
        String endpoint = certificate.getAsJsonObject().get(APIImportExportConstants.HOSTNAME_JSON_KEY)
                .getAsString();
        try {
            if (apiProvider.isCertificatePresent(tenantId, alias)
                    || (ResponseCode.ALIAS_EXISTS_IN_TRUST_STORE.getResponseCode() ==
                    (apiProvider.addCertificate(APIUtil.replaceEmailDomainBack(importedApi.getId().getProviderName()),
                            certificateContent, alias, endpoint)))) {
                apiProvider.updateCertificate(certificateContent, alias);
            }
        } catch (APIManagementException e) {
            String errorMessage = "Error while importing certificate endpoint [" + endpoint + " ]" + "alias ["
                    + alias + " ] tenant user ["
                    + APIUtil.replaceEmailDomainBack(importedApi.getId().getProviderName()) + "]";
            log.error(errorMessage, e);
        }
    }

    /**
     * This method adds API sequences to the imported API. If the sequence is a newly defined one, it is added.
     *
     * @param pathToArchive location of the extracted folder of the API
     */
    private static void addSOAPToREST(String pathToArchive, API importedApi, Registry registry)
            throws APIImportExportException {

        String inFlowFileLocation = pathToArchive + File.separator + SOAPTOREST + File.separator + IN;
        String outFlowFileLocation = pathToArchive + File.separator + SOAPTOREST + File.separator + OUT;

        //Adding in-sequence, if any
        if (CommonUtil.checkFileExistence(inFlowFileLocation)) {
            APIIdentifier apiId = importedApi.getId();
            String soapToRestLocationIn =
                    APIConstants.API_ROOT_LOCATION + RegistryConstants.PATH_SEPARATOR + apiId.getProviderName()
                            + RegistryConstants.PATH_SEPARATOR + apiId.getApiName() + RegistryConstants.PATH_SEPARATOR
                            + apiId.getVersion() + RegistryConstants.PATH_SEPARATOR
                            + SOAPToRESTConstants.SequenceGen.SOAP_TO_REST_IN_RESOURCE;
            String soapToRestLocationOut =
                    APIConstants.API_ROOT_LOCATION + RegistryConstants.PATH_SEPARATOR + apiId.getProviderName()
                            + RegistryConstants.PATH_SEPARATOR + apiId.getApiName() + RegistryConstants.PATH_SEPARATOR
                            + apiId.getVersion() + RegistryConstants.PATH_SEPARATOR
                            + SOAPToRESTConstants.SequenceGen.SOAP_TO_REST_OUT_RESOURCE;
            try {
                // Import inflow mediation logic
                Path inFlowDirectory = Paths.get(inFlowFileLocation);
                ImportMediationLogic(inFlowDirectory, registry, soapToRestLocationIn);

                // Import outflow mediation logic
                Path outFlowDirectory = Paths.get(outFlowFileLocation);
                ImportMediationLogic(outFlowDirectory, registry, soapToRestLocationOut);

            } catch (DirectoryIteratorException e) {
                throw new APIImportExportException("Error in importing SOAP to REST mediation logic", e);
            }
        }
    }

    /**
     * Method created to add inflow and outflow mediation logic
     *
     * @param flowDirectory      inflow and outflow directory
     * @param registry           Registry
     * @param soapToRestLocation folder location
     * @throws APIImportExportException
     */
    private static void ImportMediationLogic(Path flowDirectory, Registry registry, String soapToRestLocation)
            throws APIImportExportException {
        InputStream inputFlowStream = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(flowDirectory)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String method = "";
                if (fileName.split(".xml").length != 0) {
                    method = fileName.split(".xml")[0]
                            .substring(file.getFileName().toString().lastIndexOf("_") + 1);
                }
                inputFlowStream = new FileInputStream(file.toFile());
                byte[] inSeqData = IOUtils.toByteArray(inputFlowStream);
                Resource inSeqResource = (Resource) registry.newResource();
                inSeqResource.setContent(inSeqData);
                inSeqResource.addProperty(SOAPToRESTConstants.METHOD, method);
                inSeqResource.setMediaType("text/xml");
                registry.put(soapToRestLocation + RegistryConstants.PATH_SEPARATOR + file.getFileName(),
                        inSeqResource);
                IOUtils.closeQuietly(inputFlowStream);
            }
        } catch (IOException | DirectoryIteratorException e) {
            throw new APIImportExportException("Error in importing SOAP to REST mediation logic", e);
        } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
            throw new APIImportExportException("Error in storing imported SOAP to REST mediation logic", e);
        } finally {
            IOUtils.closeQuietly(inputFlowStream);
        }
    }
}

