/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Component, PropTypes } from 'react';
import { sectionTitle } from 'uiTheme/radium/forms';
import DataFreshnessSection from 'components/Forms/DataFreshnessSection';

export default class AccelerationSection extends Component {
  static propTypes = {
    fields: PropTypes.object
  }

  static getFields() {
    return DataFreshnessSection.getFields();
  }

  static validate(values) {
    return {
      ...DataFreshnessSection.validate(values)
    };
  }

  render() {
    const { fields } = this.props;
    return (
      <div>
        <h3 style={sectionTitle}>{la('Acceleration')}</h3>
        <DataFreshnessSection
          fields={fields}
          entityType='source'
        />
      </div>
    );
  }
}
