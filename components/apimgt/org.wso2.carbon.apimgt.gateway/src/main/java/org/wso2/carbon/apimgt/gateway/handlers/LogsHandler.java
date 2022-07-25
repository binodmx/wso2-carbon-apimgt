/*
 * Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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

package org.wso2.carbon.apimgt.gateway.handlers;

import org.apache.axiom.soap.SOAPBody;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.log4j.MDC;
import org.apache.synapse.AbstractSynapseHandler;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.passthru.ServerWorker;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.netbeans.lib.cvsclient.commandLine.command.log;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.handlers.logging.PerAPILogHandler;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.apimgt.impl.APIConstants;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.stream.XMLStreamException;

/**
 * This Handler can be used to log all external calls done by the api manager via synapse
 */
public class LogsHandler extends AbstractSynapseHandler {

    private static final Log correlationLog = LogFactory.getLog(APIConstants.CORRELATION_LOGGER);
    private static final Log messageTrackLog = LogFactory.getLog(APIConstants.MESSAGE_TRACK_LOGGER);

    private final String KEY_CORRELATION_ID = "CorrelationId: ";
    private final String KEY_DIRECTION = "Direction: ";
    private final String KEY_DESTINATION = "Destination: ";
    private final String KEY_SOURCE_IP = "SourceIp: ";
    private final String KEY_ORIGIN = "Origin: ";
    private final String KEY_HTTP_METHOD = "HTTPMethod: ";
    private final String KEY_HTTP_SC = "HTTPStatusCode: ";
    private final String CORRELATION_ID = "correlation_id";
    private final String HTTP_METHOD = "HTTP_METHOD";
    private final String HTTP_SC = "HTTP_SC";
    private final String ENDPOINT_PREFIX = "ENDPOINT_PREFIX";
    private final String OUT_TRANSPORT_INFO = "OutTransportInfo";
    private final String TRANSPORT_IN_URL = "TransportInURL";
    private final String SEPARATOR = ", ";

    private String logLevel = null;
    private static Map<String, String> logProperties= new ConcurrentHashMap<>();

    private static boolean isCorrelationEnabled = false;
    private static boolean isCorrelationEnabledSystemPropertyRead = false;
    private static boolean isMessageTrackingEnabled = false;
    private static boolean isMessageTrackingEnabledSystemPropertyRead = false;

    private static final String API_INFO = "API_INFO";

    private static final String AUTH_HEADER = "AUTH_HEADER";
    private static final String ORG_ID_HEADER = "ORG_ID_HEADER";
    private static final String SRC_ID_HEADER = "SRC_ID_HEADER";
    private static final String APP_ID_HEADER = "APP_ID_HEADER";
    private static final String UUID_HEADER = "UUID_HEADER";
    private static final String CORRELATION_ID_HEADER = "CORRELATION_ID_HEADER";

    private static final String REQUEST_BODY_SIZE_ERROR = "Error occurred while building the message to calculate" +
            " the response body size";
    private static final String REQUEST_EVENT_PUBLICATION_ERROR = "Cannot publish request event. ";
    private static final String RESPONSE_EVENT_PUBLICATION_ERROR = "Cannot publish response event. ";
    private static final String MESSAGE_TRACK_BUILD_MESSAGE_ERROR = "Error occurred while building the log message. ";

    private static final String REQUEST_IN = "request-in";
    private static final String REQUEST_OUT = "request-out";
    private static final String RESPONSE_IN = "response-in";
    private static final String RESPONSE_OUT = "response-out";

    private boolean isCorrelationEnabled() {
        if (!isCorrelationEnabledSystemPropertyRead) {
            String config = System.getProperty(APIConstants.ENABLE_CORRELATION_LOGS);
            if (config != null && !config.equals("")) {
                isCorrelationEnabled = Boolean.parseBoolean(config);
            }
            isCorrelationEnabledSystemPropertyRead = true;
        }
        return isCorrelationEnabled;
    }

    private boolean isMessageTrackingEnabled() {
        if (!isMessageTrackingEnabledSystemPropertyRead) {
            String config = System.getProperty(APIConstants.ENABLE_MESSAGE_TRACKING_LOGS);
            if (config != null && !config.equals("")) {
                isMessageTrackingEnabled = Boolean.parseBoolean(config);
            }
            isMessageTrackingEnabledSystemPropertyRead = true;
        }
        return isMessageTrackingEnabled;
    }

    public boolean handleRequestInFlow(MessageContext messageContext) {
        if (isCorrelationEnabled()) {
            try {
                APIInfo apiInfo = new APIInfo();
                apiInfo.setApiTo(LogUtils.getTo(messageContext));
                messageContext.setProperty(API_INFO, apiInfo);
            } catch (Exception e) {
                correlationLog.error(REQUEST_EVENT_PUBLICATION_ERROR + e.getMessage(), e);
                return false;
            }
        }
        // Track messages
        if (isMessageTrackingEnabled()) {
            try {
                org.apache.axis2.context.MessageContext axis2MessageContext =
                        ((Axis2MessageContext) messageContext).getAxis2MessageContext();
                String logMessage = KEY_CORRELATION_ID + axis2MessageContext.getProperty(CORRELATION_ID);
                logMessage += SEPARATOR + KEY_DIRECTION + "RequestIn";
                logMessage += SEPARATOR + KEY_HTTP_METHOD + axis2MessageContext.getProperty(HTTP_METHOD);
                if (messageContext.getTo() != null) {
                    logMessage += SEPARATOR + KEY_DESTINATION + messageContext.getTo().getAddress();
                }
                logMessage += SEPARATOR + KEY_SOURCE_IP + GatewayUtils.getClientIp(messageContext);
                messageTrackLog.info(logMessage);
            } catch (Exception e) {
                messageTrackLog.error(MESSAGE_TRACK_BUILD_MESSAGE_ERROR + e.getMessage(), e);
                return false;
            }
        }

        // Get the log level of if logs are enabled to the API belongs to current API request
        String log = getAPILogLevel(messageContext);
        // If it presents log the details
        if ((log) != null) {
            PerAPILogHandler.logAPI(REQUEST_IN,messageContext);
        }

        return true;
    }

    public boolean handleRequestOutFlow(MessageContext messageContext) {
        if (isCorrelationEnabled()) {
            try {
                // Set API related information to API_INFO property in messageContext
                APIInfo apiInfo = (APIInfo) messageContext.getProperty(API_INFO);
                apiInfo.setRequestSize(getContentLength(messageContext));
                apiInfo.setApiMsgUUID(messageContext.getMessageID());
                apiInfo.setApiName(LogUtils.getAPIName(messageContext));
                apiInfo.setApiCTX(LogUtils.getAPICtx(messageContext));
                apiInfo.setApiMethod(LogUtils.getRestMethod(messageContext));
                apiInfo.setApiElectedResource(LogUtils.getElectedResource(messageContext));
                apiInfo.setApiRestReqFullPath(LogUtils.getRestReqFullPath(messageContext));
                apiInfo.setApiResourceCacheKey(LogUtils.getResourceCacheKey(messageContext));
                messageContext.setProperty(API_INFO, apiInfo);

                // Set headers to relevant header properties in messageContext
                Map headers = LogUtils.getTransportHeaders(messageContext);
                if (headers != null) {
                    String authHeader = LogUtils.getAuthorizationHeader(headers);
                    String orgIdHeader = LogUtils.getOrganizationIdHeader(headers);
                    String srcIdHeader = LogUtils.getSourceIdHeader(headers);
                    String appIdHeader = LogUtils.getApplicationIdHeader(headers);
                    String uuidHeader = LogUtils.getUuidHeader(headers);
                    String correlationIdHeader = LogUtils.getCorrelationHeader(headers);
                    messageContext.setProperty(AUTH_HEADER, authHeader);
                    messageContext.setProperty(ORG_ID_HEADER, orgIdHeader);
                    messageContext.setProperty(SRC_ID_HEADER, srcIdHeader);
                    messageContext.setProperty(APP_ID_HEADER, appIdHeader);
                    messageContext.setProperty(UUID_HEADER, uuidHeader);
                    messageContext.setProperty(CORRELATION_ID_HEADER, correlationIdHeader);
                }
            } catch (Exception e) {
                correlationLog.error(REQUEST_EVENT_PUBLICATION_ERROR + e.getMessage(), e);
                return false;
            }
        }

        // Track messages
        if (isMessageTrackingEnabled()) {
            try {
                org.apache.axis2.context.MessageContext axis2MessageContext =
                        ((Axis2MessageContext) messageContext).getAxis2MessageContext();
                String logMessage = KEY_CORRELATION_ID + axis2MessageContext.getProperty(CORRELATION_ID);
                logMessage += SEPARATOR + KEY_DIRECTION + "RequestOut";
                logMessage += SEPARATOR + KEY_HTTP_METHOD + axis2MessageContext.getProperty(HTTP_METHOD);
                if (messageContext.getTo() != null) {
                    // Add endpoint address for the correlation logs
                    if (messageContext.getProperty(APIMgtGatewayConstants.SYNAPSE_ENDPOINT_ADDRESS) != null) {
                        logMessage += SEPARATOR + KEY_DESTINATION + messageContext
                                .getProperty(APIMgtGatewayConstants.SYNAPSE_ENDPOINT_ADDRESS);
                    } else {
                        logMessage += SEPARATOR + KEY_DESTINATION + messageContext.getTo().getAddress();
                    }
                }
                messageTrackLog.info(logMessage);
            } catch (Exception e) {
                messageTrackLog.error(MESSAGE_TRACK_BUILD_MESSAGE_ERROR + e.getMessage(), e);
                return false;
            }
        }
        String log = (String) messageContext.getProperty("LOG_LEVEL");
        if (log != null) {
            PerAPILogHandler.logAPI( REQUEST_OUT, messageContext);
        }
        return true;
    }

    public boolean handleResponseInFlow(MessageContext messageContext) {
        if (isCorrelationEnabled()) {
            // default API would have the property LoggedResponse as true.
            String defaultAPI = (String) messageContext.getProperty("DefaultAPI");
            if (!"true".equals(defaultAPI)) {
                try {
                    // Get properties to be logged
                    APIInfo apiInfo = (APIInfo) messageContext.getProperty(API_INFO);
                    long responseTime = getResponseTime(messageContext);
                    long beTotalLatency = getBackendLatency(messageContext);
                    long responseSize = getContentLength(messageContext);;
                    String apiResponseSC = LogUtils.getRestHttpResponseStatusCode(messageContext);
                    String applicationName = LogUtils.getApplicationName(messageContext);
                    String apiConsumerKey = LogUtils.getConsumerKey(messageContext);
                    String authHeader = (String) messageContext.getProperty(AUTH_HEADER);
                    String orgIdHeader = (String) messageContext.getProperty(ORG_ID_HEADER);
                    String srcIdHeader = (String) messageContext.getProperty(SRC_ID_HEADER);
                    String appIdHeader = (String) messageContext.getProperty(APP_ID_HEADER);
                    String uuidHeader = (String) messageContext.getProperty(UUID_HEADER);
                    String correlationIdHeader = (String) messageContext.getProperty(CORRELATION_ID_HEADER);

                    // Log correlation related properties
                    correlationLog.info(beTotalLatency + "|HTTP|" + apiInfo.getApiName()
                            + "|" + apiInfo.getApiMethod() + "|" + apiInfo.getApiCTX() + apiInfo.getApiElectedResource()
                            + "|" + apiInfo.getApiTo() + "|" + authHeader + "|" + orgIdHeader + "|" + srcIdHeader
                            + "|" + appIdHeader + "|" + uuidHeader + "|" + apiInfo.getRequestSize()
                            + "|" + responseSize + "|" + apiResponseSC + "|" + applicationName + "|" + apiConsumerKey
                            + "|" + responseTime);

                    MDC.put(APIConstants.CORRELATION_ID, correlationIdHeader);
                    MDC.remove(APIConstants.CORRELATION_ID);
                } catch (Exception e) {
                    correlationLog.error(RESPONSE_EVENT_PUBLICATION_ERROR + e.getMessage(), e);
                    return false;
                }
            }
        }
        // Track messages
        if (isMessageTrackingEnabled()) {
            try {
                org.apache.axis2.context.MessageContext axis2MessageContext =
                        ((Axis2MessageContext) messageContext).getAxis2MessageContext();
                String logMessage = KEY_CORRELATION_ID + axis2MessageContext.getProperty(CORRELATION_ID);
                logMessage += SEPARATOR + KEY_DIRECTION + "ResponseIn";
                logMessage += SEPARATOR + KEY_HTTP_SC + axis2MessageContext.getProperty(HTTP_SC);
                if (messageContext.getProperty(ENDPOINT_PREFIX) != null) {
                    logMessage += SEPARATOR + KEY_ORIGIN + messageContext.getProperty(ENDPOINT_PREFIX);
                }
                messageTrackLog.info(logMessage);
            } catch (Exception e) {
                messageTrackLog.error(MESSAGE_TRACK_BUILD_MESSAGE_ERROR + e.getMessage(), e);
                return false;
            }
        }
        return true;
    }

    public boolean handleResponseOutFlow(MessageContext messageContext) {
        // Track messages
        if (isMessageTrackingEnabled()) {
            try {
                org.apache.axis2.context.MessageContext axis2MessageContext =
                        ((Axis2MessageContext) messageContext).getAxis2MessageContext();
                String logMessage = KEY_CORRELATION_ID + axis2MessageContext.getProperty(CORRELATION_ID);
                logMessage += SEPARATOR + KEY_DIRECTION + "ResponseOut";
                logMessage += SEPARATOR + KEY_HTTP_SC + axis2MessageContext.getProperty(HTTP_SC);
                if (axis2MessageContext.getProperty(OUT_TRANSPORT_INFO) != null &&
                        axis2MessageContext.getProperty(OUT_TRANSPORT_INFO) instanceof ServerWorker) {
                    ServerWorker outTransportInfo = (ServerWorker) axis2MessageContext.getProperty(OUT_TRANSPORT_INFO);
                    org.apache.axis2.context.MessageContext requestContext = outTransportInfo.getRequestContext();
                    if (requestContext.getProperty(TRANSPORT_IN_URL) != null) {
                        logMessage += SEPARATOR + KEY_ORIGIN + requestContext.getProperty(TRANSPORT_IN_URL);
                    }
                }
                messageTrackLog.info(logMessage);
            } catch (Exception e) {
                messageTrackLog.error(MESSAGE_TRACK_BUILD_MESSAGE_ERROR + e.getMessage(), e);
                return false;
            }
        }
        return true;
    }

    /*
     * getBackendLatency
     */
    private long getBackendLatency(org.apache.synapse.MessageContext messageContext) {
        long beTotalLatency = 0;
        long beStartTime = 0;
        long beEndTime = 0;
        long executionStartTime = 0;
        try {
            if (messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_END_TIME) == null) {
                if (messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_START_TIME) != null) {
                    executionStartTime = Long.parseLong(
                            (String) messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_START_TIME));
                }
                messageContext.setProperty(APIMgtGatewayConstants.BACKEND_LATENCY,
                        System.currentTimeMillis() - executionStartTime);
                messageContext.setProperty(APIMgtGatewayConstants.BACKEND_REQUEST_END_TIME, System.currentTimeMillis());
            }
            if (messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_START_TIME) != null) {
                beStartTime = Long.parseLong(
                        (String) messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_START_TIME));
            }
            if (messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_END_TIME) != null) {
                beEndTime = (Long) messageContext.getProperty(APIMgtGatewayConstants.BACKEND_REQUEST_END_TIME);
            }
            beTotalLatency = beEndTime - beStartTime;

        } catch (Exception e) {
            correlationLog.error("Error getBackendLatency -  " + e.getMessage(), e);
        }
        return beTotalLatency;
    }

    /*
     * getResponseTime
     */
    private long getResponseTime(org.apache.synapse.MessageContext messageContext) {
        long responseTime = 0;
        try {
            long rtStartTime = 0;
            if (messageContext.getProperty(APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME) != null) {
                Object objRtStartTime = messageContext.getProperty(APIMgtGatewayConstants.REQUEST_EXECUTION_START_TIME);
                rtStartTime = (objRtStartTime == null ? 0 : Long.parseLong((String) objRtStartTime));
            }
            responseTime = System.currentTimeMillis() - rtStartTime;
        } catch (Exception e) {
            correlationLog.error("Error getResponseTime -  " + e.getMessage(), e);
        }
        return responseTime;
    }

    private long getContentLength(org.apache.synapse.MessageContext messageContext) {
        long requestSize = -1;
        org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) messageContext)
                .getAxis2MessageContext();
        Map headers = (Map) axis2MC.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        String contentLength = (String) headers.get(HttpHeaders.CONTENT_LENGTH);
        if (contentLength != null) {
            requestSize = Integer.parseInt(contentLength);
            // request size is left as -1 if chunking is enabled. this is to avoid building the message
        }
        return requestSize;
    }

    /**
     * Sync the node's map based on the user given values.
     *
     * @param map Map containing API context and logLevel
     */
    public static Map<String, String> syncAPILogData(Map<String, Object> map) {
        // get the received context and the logLevel
        String apictx = (String) map.get("context");
        String logLevel = (String) map.get("value");

        if (!APIConstants.APILogHandler.DELETE.equals(logLevel) && !APIConstants.APILogHandler.DELETE_ALL
                .equals(logLevel)) {
            // value "delete" & "deleteAll" responsible for deleting operations
            // If the values are other than than, then they should be added to the map
            logProperties.put(apictx, logLevel);
        } else {
            if (APIConstants.APILogHandler.DELETE_ALL.equals(logLevel)) {
                //handle updating already existing API values
                logProperties.clear();
            } else if (logProperties.containsKey(apictx) && APIConstants.APILogHandler.DELETE.equals(logLevel)) {
                //handle already existing hence update
                logProperties.remove(apictx);
            }
        }
        return logProperties;
    }

    public static String getLogData(String context) {
        return logProperties.get(context);
    }

    public static Map<String, String> getLogData() {
        return logProperties;
    }

    /**
     * Check if the incoming API need to be logged, if yes return the loglevel, if not return null
     *
     * @param ctx MessageContext of the incoming request
     * @return log level of the API or null if not
     */
    private String getAPILogLevel(MessageContext ctx) {
        // if the logging API data holder is empty or null return null
        if (!logProperties.isEmpty()) {
            // API REST url post fix
            String apiCtx = LogUtils.getTransportInURL(ctx);
            for (Map.Entry<String, String> entry : logProperties.entrySet()) {
                String key = entry.getKey();
                // REST URL POST FIX pizzashack/1.0.0/menu pizzashack/1.0.0/ and  pizzashack/1.0.0
                // context value pizzashack/1.0
                if (apiCtx.startsWith(key + "/") || apiCtx.equals(key)) {
                    ctx.setProperty("LOG_LEVEL", entry.getValue());
                    ctx.setProperty("API_TO", apiCtx);
                    return entry.getValue();
                }
            }
        }
        return null;
    }


    private static class APIInfo {
        private String apiName;
        private String apiCTX;
        private String apiMethod;
        private String apiTo;
        private long requestSize;
        private String apiElectedResource;
        private String apiRestReqFullPath;
        private String apiMsgUUID;
        private String apiResourceCacheKey;

        public String getApiName() {
            return apiName;
        }

        public String getApiCTX() {
            return apiCTX;
        }

        public String getApiMethod() {
            return apiMethod;
        }

        public String getApiTo() {
            return apiTo;
        }

        public long getRequestSize() {
            return requestSize;
        }

        public String getApiElectedResource() {
            return apiElectedResource;
        }

        public String getApiRestReqFullPath() {
            return apiRestReqFullPath;
        }

        public String getApiMsgUUID() {
            return apiMsgUUID;
        }

        public String getApiResourceCacheKey() {
            return apiResourceCacheKey;
        }

        public void setApiName(String apiName) {
            this.apiName = apiName;
        }

        public void setApiCTX(String apiCTX) {
            this.apiCTX = apiCTX;
        }

        public void setApiMethod(String apiMethod) {
            this.apiMethod = apiMethod;
        }

        public void setApiTo(String apiTo) {
            this.apiTo = apiTo;
        }

        public void setRequestSize(long requestSize) {
            this.requestSize = requestSize;
        }

        public void setApiElectedResource(String apiElectedResource) {
            this.apiElectedResource = apiElectedResource;
        }

        public void setApiRestReqFullPath(String apiRestReqFullPath) {
            this.apiRestReqFullPath = apiRestReqFullPath;
        }

        public void setApiMsgUUID(String apiMsgUUID) {
            this.apiMsgUUID = apiMsgUUID;
        }

        public void setApiResourceCacheKey(String apiResourceCacheKey) {
            this.apiResourceCacheKey = apiResourceCacheKey;
        }
    }
}
