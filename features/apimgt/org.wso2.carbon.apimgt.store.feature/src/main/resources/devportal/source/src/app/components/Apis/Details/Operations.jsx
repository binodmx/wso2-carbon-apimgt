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
import Typography from '@material-ui/core/Typography';
import { makeStyles, withTheme } from '@material-ui/core/styles';
import { FormattedMessage } from 'react-intl';
import Table from '@material-ui/core/Table';
import TableCell from '@material-ui/core/TableCell';
import TableRow from '@material-ui/core/TableRow';
import PropTypes from 'prop-types';
import Chip from '@material-ui/core/Chip';

/**
 *
 *
 * @param {*} props
 * @returns
 */
function RenderMethodBase(props) {
    const { theme, method } = props;
    let chipColor = theme.custom.operationChipColor
        ? theme.custom.operationChipColor[method]
        : null;
    let chipTextColor = '#000000';
    if (!chipColor) {
        console.log('Check the theme settings. The resourceChipColors is not populated properlly');
        chipColor = '#cccccc';
    } else {
        chipTextColor = theme.palette.getContrastText(theme.custom.operationChipColor[method]);
    }
    return <Chip label={method} style={{ backgroundColor: chipColor, color: chipTextColor, height: 20 }} />;
}
RenderMethodBase.propTypes = {
    theme: PropTypes.object.isRequired,
    method: PropTypes.object.isRequired,
};
const RenderMethod = withTheme(RenderMethodBase);

const useStyles = makeStyles(() => ({
    root: {
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'center',
        marginBottom: 10,
    },
    heading: {
        marginRight: 20,
    },
}));

function Operations(props) {
    const { api: {operations} } = props;
    if (!operations) {
        return <div>
            <FormattedMessage
                id='Apis.Details.Operations.notFound'
                defaultMessage='Operations Not Found'
            />
        </div>;
    }
    const classes = useStyles();

    return (
        <Table>
            {operations && operations.length !== 0 && operations.map(item => (
                <TableRow style={{ borderStyle: 'hidden' }} key={item.target + '_' + item.verb}>
                    <TableCell>
                        <Typography className={classes.heading} component='p' variant='body1'>
                            {item.target}
                        </Typography>
                    </TableCell>
                    <TableCell>
                        <RenderMethod method={item.verb.toLowerCase()} />
                    </TableCell>
                </TableRow>
            ))}
        </Table>
    );
}
Operations.propTypes = {
    api: PropTypes.object.isRequired,
};

export default Operations;
