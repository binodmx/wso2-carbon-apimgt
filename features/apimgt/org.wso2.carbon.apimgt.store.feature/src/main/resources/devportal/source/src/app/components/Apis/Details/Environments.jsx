/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import PropTypes from 'prop-types';
import { withStyles } from '@material-ui/core/styles';
import Typography from '@material-ui/core/Typography';
import ExpansionPanel from '@material-ui/core/ExpansionPanel';
import ExpansionPanelSummary from '@material-ui/core/ExpansionPanelSummary';
import ExpansionPanelDetails from '@material-ui/core/ExpansionPanelDetails';
import CloudDownloadRounded from '@material-ui/icons/CloudDownloadRounded';
import Button from '@material-ui/core/Button';
import Grid from '@material-ui/core/Grid';
import IconButton from '@material-ui/core/IconButton';
import CopyToClipboard from 'react-copy-to-clipboard';
import Tooltip from '@material-ui/core/Tooltip';
import TextField from '@material-ui/core/TextField';
import Icon from '@material-ui/core/Icon';
import API from 'AppData/api';
import Utils from 'AppData/Utils';
import Alert from 'AppComponents/Shared/Alert';
import { FormattedMessage, injectIntl } from 'react-intl';
import { ApiContext } from './ApiContext';
import FileCopyIcon from "@material-ui/icons/FileCopy";
import queryString from "query-string";
import Settings from 'Settings';

const styles = theme => ({
    buttonIcon: {
        marginRight: 10,
    },
    iconAligner: {
        display: 'flex',
        justifyContent: 'flex-start',
        alignItems: 'center',
    },
    iconEven: {
        color: theme.palette.secondary.light,
        width: theme.spacing(3),
    },
    iconOdd: {
        color: theme.palette.secondary.main,
        width: theme.spacing(3),
    },
    iconTextWrapper: {
        display: 'inline-block',
        paddingLeft: 20,
    },
    bootstrapRoot: {
        padding: 0,
        'label + &': {
            marginTop: theme.spacing(3),
        },
    },
    bootstrapInput: {
        borderRadius: 4,
        backgroundColor: theme.palette.common.white,
        border: '1px solid #ced4da',
        padding: '5px 12px',
        width: 350,
        transition: theme.transitions.create(['border-color', 'box-shadow']),
        '&:focus': {
            borderColor: '#80bdff',
            boxShadow: '0 0 0 0.2rem rgba(0,123,255,.25)',
        },
    },
    iconStyle: {
        cursor: 'pointer',
        margin: '-10px 0',
    },
    envRoot: {
        '& span, & h5, & label, & td, & li': {
            color: theme.palette.getContrastText(theme.palette.background.paper),
        }
    },
});

/**
 *  @inheritdoc
 */
class Environments extends React.Component {
    constructor(props) {
        super(props);
        this.apiClient = new API();
        this.state = {
            urlCopied: false,
            accessTokenPart: Utils.getCookieWithoutEnvironment('WSO2_AM_TOKEN_1_Default'),
            tenant: null,
        };
        this.downloadWSDL = this.downloadWSDL.bind(this);
        this.onCopy = this.onCopy.bind(this);
    }

    componentDidMount() {
        const { app: { customUrl: { tenantDomain: customUrlEnabledDomain } } } = Settings;
        let tenantDomain = null;
        if (customUrlEnabledDomain !== 'null') {
            tenantDomain = customUrlEnabledDomain;
        } else {
            const { location } = window;
            if (location) {
                const { tenant } = queryString.parse(location.search);
                if (tenant) {
                    tenantDomain = tenant;
                }
            }
        }
        this.setState({ tenant: tenantDomain });
    }

    onCopy = (name) => {
        this.setState({
            [name]: true,
        });
        const that = this;
        const caller = function () {
            that.setState({ urlCopied: false });
        };
        setTimeout(caller, 2000);
    }

    /**
     * Downloads the WSDL of the api for the provided environment
     *
     * @param {string} apiId uuid of the API
     * @param {string} environmentName name of the environment
     */
    downloadWSDL(apiId, environmentName) {
        const { intl } = this.props;
        const wsdlClient = this.apiClient.getWsdlClient();
        const promisedGet = wsdlClient.downloadWSDLForEnvironment(apiId, environmentName);
        promisedGet
            .then((done) => {
                Utils.downloadFile(done);
            })
            .catch((error) => {
                if (process.env.NODE_ENV !== 'production') {
                    console.log(error);
                    Alert.error(intl.formatMessage({
                        id: 'Apis.Details.Environments.download.wsdl.error',
                        defaultMessage: 'Error downloading the WSDL',
                    }));
                }
            });
    }

    /**
     * Downloads the swagger of the api for the provided environment
     *
     * @param {string} apiId uuid of the API
     * @param {string} environment name of the environment
     */
    downloadSwagger(apiId, environment) {
        const promiseSwagger = this.apiClient.getSwaggerByAPIIdAndEnvironment(apiId, environment);
        promiseSwagger
            .then((done) => {
                Utils.downloadFile(done);
            })
            .catch((error) => {
                console.log(error);
                Alert.error(intl.formatMessage({
                    id: 'Apis.Details.Environments.download.swagger.error',
                    defaultMessage: 'Error downloading the Swagger',
                }));
            });
    }

    /**
     *  @inheritdoc
     */
    render() {
        const { api } = this.context;
        const { classes, intl } = this.props;
        const { urlCopied, accessTokenPart, tenant } = this.state;
        // let kubernetes = 'kuberenetes'

        // let kubernetes = [{
        //     "EnvName" : "Kubernetes",
        //     "clusterDetails" : [
        //         {
        //             "clusterName" : "Docker-desktop",
        //             "ingressURL" : "http://wso2.com:9443"
        //         },
        //         {
        //             "clusterName" : "Minikube",
        //             "ingressURL" : "http://wso2.system.com:9443"
        //         }
        //     ]
        // }]

        let kubernetes = []

        return (
            <Grid container spacing={2} item xs={12} className={classes.envRoot}>
                {api.endpointURLs.map((endpoint) => {
                    return (
                        <Grid key={endpoint} item xs={12} key={endpoint.environmentName}>
                            <ExpansionPanel>
                                <ExpansionPanelSummary
                                    expandIcon={<Icon>expand_more</Icon>}
                                    aria-controls='panel1a-content'
                                    id='panel1a-header'
                                >
                                    <div className={classes.iconAligner}>
                                        {endpoint.environmentType === 'hybrid' && (
                                            <Icon className={classes.iconEven}>cloud</Icon>
                                        )}
                                        {endpoint.environmentType === 'production' && (
                                            <Icon className={classes.iconEven}>check_circle</Icon>
                                        )}
                                        {endpoint.environmentType === 'sandbox' && (
                                            <Icon className={classes.iconEven}>Build</Icon>
                                        )}
                                        <span className={classes.iconTextWrapper}>
                                            <Typography className={classes.heading}>
                                                {endpoint.environmentName}
                                            </Typography>
                                        </span>
                                    </div>
                                </ExpansionPanelSummary>
                                <ExpansionPanelDetails>
                                    <Grid container item xs={12} spacing={2}>
                                        {(endpoint.URLs.http !== null
                                            || endpoint.URLs.https !== null
                                            || endpoint.URLs.ws !== null
                                            || endpoint.URLs.wss !== null) && (
                                                <Typography id='urls' component='label' className={classes.heading}>
                                                    <FormattedMessage
                                                        id='Apis.Details.InfoBar.gateway.urls'
                                                        defaultMessage='Gateway URLs'
                                                    />
                                                </Typography>
                                            )}
                                        {endpoint.URLs.http !== null && (
                                            <Grid item xs={12}>
                                                <Tooltip
                                                    placement='right'
                                                    title={
                                                        (api.type === 'GRAPHQL')
                                                            ? (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.GraphQL.Http'}
                                                                    defaultMessage={'Gateway HTTP URL for GraphQL'
                                                                        + ' Queries and Mutations'}
                                                                />
                                                            )
                                                            : (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.Http'}
                                                                    defaultMessage='Gateway HTTP URL'
                                                                />
                                                            )
                                                    }
                                                >
                                                    <TextField
                                                        defaultValue={endpoint.URLs.http}
                                                        id='bootstrap-input'
                                                        InputProps={{
                                                            disableUnderline: true,
                                                            readOnly: true,
                                                            classes: {
                                                                root: classes.bootstrapRoot,
                                                                input: classes.bootstrapInput,
                                                            },
                                                        }}
                                                        InputLabelProps={{
                                                            shrink: true,
                                                            className: classes.bootstrapFormLabel,
                                                        }}
                                                        inputProps={{
                                                            'aria-labelledby': 'urls',
                                                        }}
                                                    />
                                                </Tooltip>
                                                <Tooltip
                                                    title={
                                                        urlCopied
                                                            ? intl.formatMessage({
                                                                defaultMessage: 'Copied',
                                                                id: 'Apis.Details.Environments.copied',
                                                            })
                                                            : intl.formatMessage({
                                                                defaultMessage: 'Copy to clipboard',
                                                                id: 'Apis.Details.Environments.copy.to.clipboard',
                                                            })
                                                    }
                                                    placement='right'
                                                    className={classes.iconStyle}
                                                >
                                                    <CopyToClipboard
                                                        text={endpoint.URLs.http}
                                                        onCopy={() => this.onCopy('urlCopied')}
                                                    >
                                                        <IconButton aria-label='Copy to clipboard'>
                                                            <Icon color='secondary'>insert_drive_file</Icon>
                                                        </IconButton>
                                                    </CopyToClipboard>
                                                </Tooltip>
                                            </Grid>
                                        )}
                                        {endpoint.URLs.https !== null && (
                                            <Grid item xs={12}>
                                                <Tooltip
                                                    placement='right'
                                                    title={
                                                        (api.type === 'GRAPHQL')
                                                            ? (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.GraphQL.Https'}
                                                                    defaultMessage={'Gateway HTTPs URL for GraphQL'
                                                                        + ' Queries and Mutations'}
                                                                />
                                                            )
                                                            : (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.Https'}
                                                                    defaultMessage='Gateway HTTPs URL'
                                                                />
                                                            )
                                                    }
                                                >
                                                    <TextField
                                                        defaultValue={endpoint.URLs.https}
                                                        id='environments-input'
                                                        InputProps={{
                                                            disableUnderline: true,
                                                            readOnly: true,
                                                            classes: {
                                                                root: classes.bootstrapRoot,
                                                                input: classes.bootstrapInput,
                                                            },
                                                        }}
                                                        InputLabelProps={{
                                                            shrink: true,
                                                            className: classes.bootstrapFormLabel,
                                                        }}
                                                        inputProps={{
                                                            'aria-labelledby': 'urls',
                                                        }}
                                                    />
                                                </Tooltip>
                                                <Tooltip
                                                    title={
                                                        urlCopied
                                                            ? intl.formatMessage({
                                                                defaultMessage: 'Copied',
                                                                id: 'Apis.Details.Environments.copied',
                                                            })
                                                            : intl.formatMessage({
                                                                defaultMessage: 'Copy to clipboard',
                                                                id: 'Apis.Details.Environments.copy.to.clipboard',
                                                            })
                                                    }
                                                    placement='right'
                                                    className={classes.iconStyle}
                                                >
                                                    <CopyToClipboard
                                                        text={endpoint.URLs.https}
                                                        onCopy={() => this.onCopy('urlCopied')}
                                                    >
                                                        <IconButton aria-label='Copy to clipboard'>
                                                            <Icon color='secondary'>insert_drive_file</Icon>
                                                        </IconButton>
                                                    </CopyToClipboard>
                                                </Tooltip>
                                            </Grid>
                                        )}
                                        {endpoint.URLs.ws !== null && (
                                            <Grid item xs={12}>
                                                <Tooltip
                                                    placement='right'
                                                    title={
                                                        (api.type === 'GRAPHQL')
                                                            ? (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.GraphQL.Ws'}
                                                                    defaultMessage={'Gateway WebSocket'
                                                                        + ' URL for GraphQL Subscriptions'}
                                                                />
                                                            )
                                                            : (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.Ws'}
                                                                    defaultMessage='Gateway WebSocket URL'
                                                                />
                                                            )
                                                    }
                                                >
                                                    <TextField
                                                        defaultValue={endpoint.URLs.ws}
                                                        id='bootstrap-input'
                                                        InputProps={{
                                                            disableUnderline: true,
                                                            readOnly: true,
                                                            classes: {
                                                                root: classes.bootstrapRoot,
                                                                input: classes.bootstrapInput,
                                                            },
                                                        }}
                                                        InputLabelProps={{
                                                            shrink: true,
                                                            className: classes.bootstrapFormLabel,
                                                        }}
                                                    />
                                                </Tooltip>
                                                <Tooltip
                                                    title={
                                                        urlCopied
                                                            ? intl.formatMessage({
                                                                defaultMessage: 'Copied',
                                                                id: 'Apis.Details.Environments.copied',
                                                            })
                                                            : intl.formatMessage({
                                                                defaultMessage: 'Copy to clipboard',
                                                                id: 'Apis.Details.Environments.copy.to.clipboard',
                                                            })
                                                    }
                                                    placement='right'
                                                    className={classes.iconStyle}
                                                >
                                                    <CopyToClipboard
                                                        text={endpoint.URLs.ws}
                                                        onCopy={() => this.onCopy('urlCopied')}
                                                    >
                                                        <IconButton aria-label='Copy to clipboard'>
                                                            <Icon color='secondary'>insert_drive_file</Icon>
                                                        </IconButton>
                                                    </CopyToClipboard>
                                                </Tooltip>
                                            </Grid>
                                        )}
                                        {endpoint.URLs.wss !== null && (
                                            <Grid item xs={12}>
                                                <Tooltip
                                                    placement='right'
                                                    title={
                                                        (api.type === 'GRAPHQL')
                                                            ? (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.GraphQL.Wss'}
                                                                    defaultMessage={'Gateway WebSocket Secure'
                                                                        + ' URL for GraphQL Subscriptions'}
                                                                />
                                                            )
                                                            : (
                                                                <FormattedMessage
                                                                    id={'Apis.Details.Environments.Environments.'
                                                                        + 'APIGateways.EndpointUrls.Wss'}
                                                                    defaultMessage='Gateway WebSocket Secure URL'
                                                                />
                                                            )
                                                    }
                                                >
                                                    <TextField
                                                        defaultValue={endpoint.URLs.wss}
                                                        id='bootstrap-input'
                                                        InputProps={{
                                                            disableUnderline: true,
                                                            readOnly: true,
                                                            classes: {
                                                                root: classes.bootstrapRoot,
                                                                input: classes.bootstrapInput,
                                                            },
                                                        }}
                                                        InputLabelProps={{
                                                            shrink: true,
                                                            className: classes.bootstrapFormLabel,
                                                        }}
                                                    />
                                                </Tooltip>
                                                <Tooltip
                                                    title={
                                                        urlCopied
                                                            ? intl.formatMessage({
                                                                defaultMessage: 'Copied',
                                                                id: 'Apis.Details.Environments.copied',
                                                            })
                                                            : intl.formatMessage({
                                                                defaultMessage: 'Copy to clipboard',
                                                                id: 'Apis.Details.Environments.copy.to.clipboard',
                                                            })
                                                    }
                                                    placement='right'
                                                    className={classes.iconStyle}
                                                >
                                                    <CopyToClipboard
                                                        text={endpoint.URLs.wss}
                                                        onCopy={() => this.onCopy('urlCopied')}
                                                    >
                                                        <IconButton aria-label='Copy to clipboard'>
                                                            <Icon color='secondary'>insert_drive_file</Icon>
                                                        </IconButton>
                                                    </CopyToClipboard>
                                                </Tooltip>
                                            </Grid>
                                        )}
                                        {endpoint.defaultVersionURLs !== null &&
                                            (endpoint.defaultVersionURLs.http !== null ||
                                                endpoint.defaultVersionURLs.https !== null ||
                                                endpoint.defaultVersionURLs.ws !== null ||
                                                endpoint.defaultVersionURLs.wss !== null) && (
                                                <Typography className={classes.heading} component='label' htmlFor='defaultGateway'>
                                                    <FormattedMessage
                                                        id='Apis.Details.InfoBar.default.gateway.urls'
                                                        defaultMessage='Default Gateway URLs'
                                                    />
                                                </Typography>
                                            )}
                                        {endpoint.defaultVersionURLs !== null &&
                                            endpoint.defaultVersionURLs.http !== null && (
                                                <Grid item xs={12}>
                                                    <Tooltip
                                                        placement='right'
                                                        title={
                                                            (api.type === 'GRAPHQL')
                                                                ? (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.GraphQL.Http'}
                                                                        defaultMessage={'Gateway HTTP URL for GraphQL'
                                                                            + ' Queries and Mutations'}
                                                                    />
                                                                )
                                                                : (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.Http'}
                                                                        defaultMessage='Gateway HTTP URL'
                                                                    />
                                                                )
                                                        }
                                                    >
                                                        <TextField
                                                            defaultValue={endpoint.defaultVersionURLs.http}
                                                            id='defaultGateway'
                                                            InputProps={{
                                                                disableUnderline: true,
                                                                readOnly: true,
                                                                classes: {
                                                                    root: classes.bootstrapRoot,
                                                                    input: classes.bootstrapInput,
                                                                },
                                                            }}
                                                            InputLabelProps={{
                                                                shrink: true,
                                                                className: classes.bootstrapFormLabel,
                                                            }}
                                                        />
                                                    </Tooltip>
                                                    <Tooltip
                                                        title={
                                                            urlCopied
                                                                ? intl.formatMessage({
                                                                    defaultMessage: 'Copied',
                                                                    id: 'Apis.Details.Environments.copied',
                                                                })
                                                                : intl.formatMessage({
                                                                    defaultMessage: 'Copy to clipboard',
                                                                    id: 'Apis.Details.Environments.copy.to.clipboard',
                                                                })
                                                        }
                                                        placement='right'
                                                        className={classes.iconStyle}
                                                    >
                                                        <CopyToClipboard
                                                            text={endpoint.defaultVersionURLs.http}
                                                            onCopy={() => this.onCopy('urlCopied')}
                                                        >
                                                            <IconButton aria-label='Copy to clipboard'>
                                                                <Icon color='secondary'>file_copy</Icon>
                                                            </IconButton>
                                                        </CopyToClipboard>
                                                    </Tooltip>
                                                </Grid>
                                            )}
                                        {endpoint.defaultVersionURLs !== null &&
                                            endpoint.defaultVersionURLs.https !== null && (
                                                <Grid item xs={12}>
                                                    <Tooltip
                                                        placement='right'
                                                        title={
                                                            (api.type === 'GRAPHQL')
                                                                ? (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.GraphQL.Https'}
                                                                        defaultMessage={'Gateway HTTPs URL for GraphQL'
                                                                            + ' Queries and Mutations'}
                                                                    />
                                                                )
                                                                : (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.Https'}
                                                                        defaultMessage='Gateway HTTPs URL'
                                                                    />
                                                                )
                                                        }
                                                    >
                                                        <TextField
                                                            defaultValue={endpoint.defaultVersionURLs.https}
                                                            id='bootstrap-input'
                                                            InputProps={{
                                                                disableUnderline: true,
                                                                readOnly: true,
                                                                classes: {
                                                                    root: classes.bootstrapRoot,
                                                                    input: classes.bootstrapInput,
                                                                },
                                                            }}
                                                            InputLabelProps={{
                                                                shrink: true,
                                                                className: classes.bootstrapFormLabel,
                                                            }}
                                                        />
                                                    </Tooltip>
                                                    <Tooltip
                                                        title={
                                                            urlCopied
                                                                ? intl.formatMessage({
                                                                    defaultMessage: 'Copied',
                                                                    id: 'Apis.Details.Environments.copied',
                                                                })
                                                                : intl.formatMessage({
                                                                    defaultMessage: 'Copy to clipboard',
                                                                    id: 'Apis.Details.Environments.copy.to.clipboard',
                                                                })
                                                        }
                                                        placement='right'
                                                        className={classes.iconStyle}
                                                    >
                                                        <CopyToClipboard
                                                            text={endpoint.defaultVersionURLs.https}
                                                            onCopy={() => this.onCopy('urlCopied')}
                                                        >
                                                            <IconButton aria-label='Copy to clipboard'>
                                                                <Icon color='secondary'>file_copy</Icon>
                                                            </IconButton>
                                                        </CopyToClipboard>
                                                    </Tooltip>
                                                </Grid>
                                            )}
                                        {endpoint.defaultVersionURLs !== null &&
                                            endpoint.defaultVersionURLs.ws !== null && (
                                                <Grid item xs={12}>
                                                    <Tooltip
                                                        placement='right'
                                                        title={
                                                            (api.type === 'GRAPHQL')
                                                                ? (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.GraphQL.Ws'}
                                                                        defaultMessage={'Gateway WebSocket'
                                                                            + ' URL for GraphQL Subscriptions'}
                                                                    />
                                                                )
                                                                : (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.Ws'}
                                                                        defaultMessage='Gateway WebSocket URL'
                                                                    />
                                                                )
                                                        }
                                                    >
                                                        <TextField
                                                            defaultValue={endpoint.defaultVersionURLs.ws}
                                                            id='bootstrap-input'
                                                            InputProps={{
                                                                disableUnderline: true,
                                                                readOnly: true,
                                                                classes: {
                                                                    root: classes.bootstrapRoot,
                                                                    input: classes.bootstrapInput,
                                                                },
                                                            }}
                                                            InputLabelProps={{
                                                                shrink: true,
                                                                className: classes.bootstrapFormLabel,
                                                            }}
                                                        />
                                                    </Tooltip>
                                                    <Tooltip
                                                        title={
                                                            urlCopied
                                                                ? intl.formatMessage({
                                                                    defaultMessage: 'Copied',
                                                                    id: 'Apis.Details.Environments.copied',
                                                                })
                                                                : intl.formatMessage({
                                                                    defaultMessage: 'Copy to clipboard',
                                                                    id: 'Apis.Details.Environments.copy.to.clipboard',
                                                                })
                                                        }
                                                        placement='right'
                                                        className={classes.iconStyle}
                                                    >
                                                        <CopyToClipboard
                                                            text={endpoint.defaultVersionURLs.ws}
                                                            onCopy={() => this.onCopy('urlCopied')}
                                                        >
                                                            <IconButton aria-label='Copy to clipboard'>
                                                                <Icon color='secondary'>file_copy</Icon>
                                                            </IconButton>
                                                        </CopyToClipboard>
                                                    </Tooltip>
                                                </Grid>
                                            )}
                                        {endpoint.defaultVersionURLs !== null &&
                                            endpoint.defaultVersionURLs.wss !== null && (
                                                <Grid item xs={12}>
                                                    <Tooltip
                                                        placement='right'
                                                        title={
                                                            (api.type === 'GRAPHQL')
                                                                ? (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.GraphQL.Wss'}
                                                                        defaultMessage={'Gateway WebSocket Secure'
                                                                            + ' URL for GraphQL Subscriptions'}
                                                                    />
                                                                )
                                                                : (
                                                                    <FormattedMessage
                                                                        id={'Apis.Details.Environments.Environments.'
                                                                            + 'APIGateways.EndpointUrls.Wss'}
                                                                        defaultMessage='Gateway WebSocket Secure URL'
                                                                    />
                                                                )
                                                        }
                                                    >
                                                        <TextField
                                                            defaultValue={endpoint.defaultVersionURLs.wss}
                                                            id='bootstrap-input'
                                                            InputProps={{
                                                                disableUnderline: true,
                                                                readOnly: true,
                                                                classes: {
                                                                    root: classes.bootstrapRoot,
                                                                    input: classes.bootstrapInput,
                                                                },
                                                            }}
                                                            InputLabelProps={{
                                                                shrink: true,
                                                                className: classes.bootstrapFormLabel,
                                                            }}
                                                        />
                                                    </Tooltip>
                                                    <Tooltip
                                                        title={
                                                            urlCopied
                                                                ? intl.formatMessage({
                                                                    defaultMessage: 'Copied',
                                                                    id: 'Apis.Details.Environments.copied',
                                                                })
                                                                : intl.formatMessage({
                                                                    defaultMessage: 'Copy to clipboard',
                                                                    id: 'Apis.Details.Environments.copy.to.clipboard',
                                                                })
                                                        }
                                                        placement='right'
                                                        className={classes.iconStyle}
                                                    >
                                                        <CopyToClipboard
                                                            text={endpoint.defaultVersionURLs.wss}
                                                            onCopy={() => this.onCopy('urlCopied')}
                                                        >
                                                            <IconButton aria-label='Copy to clipboard'>
                                                                <Icon color='secondary'>file_copy</Icon>
                                                            </IconButton>
                                                        </CopyToClipboard>
                                                    </Tooltip>
                                                </Grid>
                                            )}
                                        {api.type === 'SOAP' && (
                                            <Button
                                                size='small'
                                                onClick={() => this.downloadWSDL(api.id, endpoint.environmentName)}
                                            >
                                                <CloudDownloadRounded className={classes.buttonIcon} />
                                                <FormattedMessage
                                                    id='Apis.Details.Environments.download.wsdl'
                                                    defaultMessage='WSDL'
                                                />
                                            </Button>
                                        )}
                                        {(api.type === 'HTTP' || api.type === 'SOAPTOREST') && (
                                            <Grid container>
                                                <Grid xs={2} item>
                                                    <Button
                                                        size='small'
                                                        onClick={() => this.downloadSwagger(api.id, endpoint.environmentName)}
                                                        data-testid='swagger-download-btn'
                                                    >
                                                        <CloudDownloadRounded className={classes.buttonIcon} />
                                                        <FormattedMessage
                                                            id='Apis.Details.Environments.download.swagger'
                                                            defaultMessage='Swagger'
                                                        />
                                                    </Button>
                                                </Grid>
                                                <Grid xs={2} item>
                                                    <Tooltip
                                                        title={urlCopied
                                                            ? (
                                                                <FormattedMessage
                                                                    id='Apis.Details.Swagger.URL.copied'
                                                                    defaultMessage='Copied'
                                                                />
                                                            )
                                                            : (
                                                                <FormattedMessage
                                                                    id='Apis.Details.Swagger.URL.copy.to.clipboard'
                                                                    defaultMessage='Copy to clipboard'
                                                                />
                                                            )}
                                                        placement='top'
                                                    >
                                                        <CopyToClipboard
                                                            text={location.origin + '/api/am/store/v1/apis/' + api.id
                                                            + '/swagger?accessToken=' + accessTokenPart
                                                            + '&X-WSO2-Tenant-Q=' + tenant + '&' + 'environmentName='
                                                            + endpoint.environmentName}
                                                            onCopy={() => this.onCopy('urlCopied')}
                                                        >
                                                            <Button aria-label='Copy to clipboard' size='small'>
                                                                <FileCopyIcon className={classes.buttonIcon} />
                                                            </Button>
                                                        </CopyToClipboard>
                                                    </Tooltip>
                                                </Grid>
                                            </Grid>
                                        )}
                                    </Grid>
                                </ExpansionPanelDetails>
                            </ExpansionPanel>
                        </Grid>
                    );
                })}
                {api.ingressURLs !== null && api.ingressURLs.filter((envType) => envType.clusterDetails.length > 0).map(
                    (envType) => (
                        envType.clusterDetails.map((cluster) => (
                            <Grid key={cluster.clusterName} item xs={12} key={cluster.clusterName}>
                                <ExpansionPanel>
                                    <ExpansionPanelSummary
                                        expandIcon={<Icon>expand_more</Icon>}
                                        aria-controls='panel1a-content'
                                        id='panel1a-header-cluster'
                                    >
                                        <div className={classes.iconAligner}>
                                            <Icon className={classes.iconEven}>cloud</Icon>
                                            <span className={classes.iconTextWrapper}>
                                                <Typography className={classes.heading}>
                                                    {cluster.clusterDisplayName}
                                                </Typography>
                                            </span>
                                        </div>
                                    </ExpansionPanelSummary>
                                    <ExpansionPanelDetails>
                                        <Grid container item xs={12} spacing={2}>
                                            {cluster.ingressURL !== null && (
                                                <Typography className={classes.heading} component='label' htmlFor='ingressUrl'>
                                                    <FormattedMessage
                                                        id='Apis.Details.InfoBar.gateway.ingressUrls'
                                                        defaultMessage='Cluster Ingress URLs'
                                                    />
                                                </Typography>
                                            )}
                                            {cluster.ingressURL !== null && (
                                                <Grid item xs={12}>
                                                    <TextField
                                                        defaultValue={cluster.ingressURL}
                                                        id='ingressUrl'
                                                        InputProps={{
                                                            disableUnderline: true,
                                                            readOnly: true,
                                                            classes: {
                                                                root: classes.bootstrapRoot,
                                                                input: classes.bootstrapInput,
                                                            },
                                                        }}
                                                        InputLabelProps={{
                                                            shrink: true,
                                                            className: classes.bootstrapFormLabel,
                                                        }}
                                                    />
                                                    <Tooltip
                                                        title={
                                                            urlCopied
                                                                ? intl.formatMessage({
                                                                    defaultMessage: 'Copied',
                                                                    id: 'Apis.Details.Environments.copied',
                                                                })
                                                                : intl.formatMessage({
                                                                    defaultMessage: 'Copy to clipboard',
                                                                    id: 'Apis.Details.Environments.copy.to.clipboard',
                                                                })
                                                        }
                                                        placement='right'
                                                        className={classes.iconStyle}
                                                    >
                                                        <CopyToClipboard
                                                            text={cluster.ingressURL}
                                                            onCopy={() => this.onCopy('urlCopied')}
                                                        >
                                                            <IconButton aria-label='Copy to clipboard'>
                                                                <Icon color='secondary'>insert_drive_file</Icon>
                                                            </IconButton>
                                                        </CopyToClipboard>
                                                    </Tooltip>
                                                </Grid>
                                            )}
                                        </Grid>
                                    </ExpansionPanelDetails>
                                </ExpansionPanel>
                            </Grid>
                        ))
                    ))
                }
            </Grid>
        );
    }
}

Environments.propTypes = {
    classes: PropTypes.object.isRequired,
    intl: PropTypes.shape({}).isRequired,
};
Environments.contextType = ApiContext;

export default injectIntl(withStyles(styles)(Environments));
