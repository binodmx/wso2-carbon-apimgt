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
package org.wso2.carbon.apimgt.gateway;

import org.wso2.carbon.apimgt.gateway.handlers.InboundMessageContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A gateway data holder to hold information of InboundMessageContext and connection which it associates with.
 */
public class InboundMessageContextDataHolder {

    private static final InboundMessageContextDataHolder instance = new InboundMessageContextDataHolder();
    private Map<String, InboundMessageContext> inboundMessageContextMap = new ConcurrentHashMap<>();

    public static InboundMessageContextDataHolder getInstance() {
        return instance;
    }

    public Map<String, InboundMessageContext> getInboundMessageContextMap() {
        return inboundMessageContextMap;
    }

    public void addInboundMessageContextForConnection(String connectionId,
                                                      InboundMessageContext inboundMessageContext) {
        inboundMessageContextMap.put(connectionId, inboundMessageContext);
    }

    public InboundMessageContext putIfAbsentInboundMessageContextForConnection(String connectionId,
                                                      InboundMessageContext inboundMessageContext) {
        return inboundMessageContextMap.putIfAbsent(connectionId, inboundMessageContext);
    }

    public InboundMessageContext getInboundMessageContextForConnectionId(String connectionId) {
        return inboundMessageContextMap.get(connectionId);
    }

    public void removeInboundMessageContextForConnection(String connectionId) {
        inboundMessageContextMap.remove(connectionId);
    }
}
