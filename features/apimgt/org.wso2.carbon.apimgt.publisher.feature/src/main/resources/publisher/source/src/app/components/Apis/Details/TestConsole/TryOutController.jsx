/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import React from 'react';
import { FormattedMessage } from 'react-intl';
import Grid from '@material-ui/core/Grid';
import Typography from '@material-ui/core/Typography';
import PropTypes from 'prop-types';
import withStyles from '@material-ui/core/styles/withStyles';
import { makeStyles } from '@material-ui/core/styles';
import TextField from '@material-ui/core/TextField';
import MenuItem from '@material-ui/core/MenuItem';
import Box from '@material-ui/core/Box';
import Progress from '../../../Shared/Progress';

/**
 * @inheritdoc
 * @param {*} theme theme
 */
const styles = makeStyles((theme) => ({
    centerItems: {
        margin: 'auto',
    },
    gatewayEnvironment: {
        marginTop: theme.spacing(4),
    },
    categoryHeading: {
        marginBottom: theme.spacing(2),
        marginLeft: theme.spacing(-5),
    },
    menuItem: {
        color: theme.palette.getContrastText(theme.palette.background.paper),
    },
}));

/**
 * TryOut component
 *
 * @class TryOutController
 * @extends {Component}
 */
function TryOutController(props) {
    const {
        selectedEnvironment, environments, containerMngEnvironments, labels,
        setSelectedEnvironment, updateSwagger,
        environmentObject, setURLs, api,
    } = props;

    const classes = styles();

    /**
     * Handle onChange of inputs
     * @param {*} event event
     * @memberof TryOutController
     */
    function handleChanges(event) {
        const { target } = event;
        const { name, value } = target;
        switch (name) {
            case 'selectedEnvironment':
                setSelectedEnvironment(value, true);
                if (api.type !== 'GRAPHQL') {
                    updateSwagger(value);
                }
                if (environmentObject) {
                    const urls = environmentObject.find((elm) => value === elm.environmentName).URLs;
                    setURLs(urls);
                }
                break;
            default:
        }
    }

    if (api == null) {
        return <Progress />;
    }

    const isPrototypedAPI = api.lifeCycleStatus && api.lifeCycleStatus.toLowerCase() === 'prototyped';

    // The rendering logic of container management menus items are done here
    // because when grouping container management type and clusters with <> and </>
    // the handleChange event is not triggered. Hence handle rendering logic here.
    const containerMngEnvMenuItems = [];
    if (containerMngEnvironments) {
        containerMngEnvironments.filter((envType) => envType.clusterDetails.length > 0).forEach((envType) => {
            // container management system type
            containerMngEnvMenuItems.push(
                <MenuItem value='' disabled className={classes.menuItem}>
                    <em>
                        {envType.deploymentEnvironmentName}
                    </em>
                </MenuItem>,
            );
            // clusters of the container management system type
            envType.clusterDetails.forEach((cluster) => {
                containerMngEnvMenuItems.push(
                    <MenuItem
                        value={cluster.clusterName}
                        key={cluster.clusterName}
                        className={classes.menuItem}
                    >
                        {cluster.clusterDisplayName}
                    </MenuItem>,
                );
            });
        });
    }


    return (
        <>
            {(isPrototypedAPI && !api.enableStore)
                && (
                    <Box display='flex' justifyContent='center' className={classes.gatewayEnvironment}>
                        <Grid xs={12} md={6} item>
                            {((environments && environments.length > 0) || (containerMngEnvMenuItems.length > 0)
                                || (labels && labels.length > 0))
                                && (
                                    <>
                                        <Typography
                                            variant='h5'
                                            component='h3'
                                            color='textPrimary'
                                            className={classes.categoryHeading}
                                        >
                                            <FormattedMessage
                                                id='api.console.gateway.heading'
                                                defaultMessage='Gateway'
                                            />
                                        </Typography>
                                        <TextField
                                            fullWidth
                                            select
                                            label={(
                                                <FormattedMessage
                                                    defaultMessage='Environment'
                                                    id='Apis.Details.ApiConsole.environment'
                                                />
                                            )}
                                            value={selectedEnvironment || (environments && environments[0])}
                                            name='selectedEnvironment'
                                            onChange={handleChanges}
                                            helperText={(
                                                <FormattedMessage
                                                    defaultMessage='Please select an environment'
                                                    id='Apis.Details.ApiConsole.SelectAppPanel.environment'
                                                />
                                            )}
                                            margin='normal'
                                            variant='outlined'
                                        >
                                            {environments && environments.length > 0 && (
                                                <MenuItem value='' disabled className={classes.menuItem}>
                                                    <em>
                                                        <FormattedMessage
                                                            id='api.gateways'
                                                            defaultMessage='API Gateways'
                                                        />
                                                    </em>
                                                </MenuItem>
                                            )}
                                            {environments && (
                                                environments.map((env) => (
                                                    <MenuItem
                                                        value={env}
                                                        key={env}
                                                        className={classes.menuItem}
                                                    >
                                                        {env}
                                                    </MenuItem>
                                                )))}
                                            {containerMngEnvMenuItems}
                                            {labels && labels.length > 0 && (
                                                <MenuItem value='' disabled>
                                                    <em>
                                                        <FormattedMessage
                                                            id='gateways'
                                                            defaultMessage='Gateways'
                                                            className={classes.menuItem}
                                                        />
                                                    </em>
                                                </MenuItem>
                                            )}
                                            {labels && (
                                                labels.map((label) => (
                                                    <MenuItem
                                                        value={label}
                                                        key={label}
                                                        className={classes.menuItem}
                                                    >
                                                        {label}
                                                    </MenuItem>
                                                ))
                                            )}
                                        </TextField>
                                    </>
                                )}
                        </Grid>
                    </Box>
                )}
        </>
    );
}

TryOutController.propTypes = {
    classes: PropTypes.shape({}).isRequired,
};

export default withStyles(makeStyles)(TryOutController);
