/*
 *
 *   Copyright (c) 2023, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.carbon.apimgt.rest.api.devops;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.modules.junit4.PowerMockRunner;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.rest.api.devops.dto.CorrelationComponentDTO;
import org.wso2.carbon.apimgt.rest.api.devops.dto.CorrelationComponentsListDTO;

import java.util.ArrayList;
import java.util.List;

@RunWith(PowerMockRunner.class)
public class RestApiDevopsUtilTest {

    @Test
    public void testValidateCorrelationComponentList() throws APIManagementException {
        List<CorrelationComponentDTO> correlationComponentDTOList = new ArrayList<>();
        for (int i = 0; i < DevopsAPIUtils.CORRELATION_DEFAULT_COMPONENTS.length; i++) {
            CorrelationComponentDTO correlationComponentDTO = new CorrelationComponentDTO();
            correlationComponentDTO.setName(DevopsAPIUtils.CORRELATION_DEFAULT_COMPONENTS[i]);
            correlationComponentDTO.setEnabled("true");
            correlationComponentDTOList.add(correlationComponentDTO);
        }
        CorrelationComponentsListDTO correlationComponentsListDTO = new CorrelationComponentsListDTO();
        correlationComponentsListDTO.setComponents(correlationComponentDTOList);
        Boolean valid = DevopsAPIUtils.validateCorrelationComponentList(correlationComponentsListDTO);
        Assert.assertTrue(valid);

        CorrelationComponentDTO correlationComponentDTO = new CorrelationComponentDTO();
        correlationComponentDTO.setName("abc");
        correlationComponentDTOList.add(correlationComponentDTO);
        correlationComponentsListDTO.setComponents(correlationComponentDTOList);
        try {
            valid = DevopsAPIUtils.validateCorrelationComponentList(correlationComponentsListDTO);
        } catch (APIManagementException e) {
            return;
        }
        Assert.assertTrue("validateCorrelationComponentList did not throw an exception", false);


    }
}
