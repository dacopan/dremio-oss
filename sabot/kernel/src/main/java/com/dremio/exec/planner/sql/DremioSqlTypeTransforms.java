/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.exec.planner.sql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.type.SqlTypeTransform;
import org.apache.calcite.sql.type.SqlTypeTransforms;

/**
 * Extension to Calcite's SqlTypeTransforms
 */
public class DremioSqlTypeTransforms extends SqlTypeTransforms {
  private DremioSqlTypeTransforms() { }

  /**
   * Parameter type-inference transform strategy where a derived type is transformed into the same type,
   * but with max precision.
   */
  public static final SqlTypeTransform MAX_PRECISION = new SqlTypeTransform() {
    @Override
    public RelDataType transformType(SqlOperatorBinding opBinding, RelDataType typeToTransform) {
      RelDataTypeFactory relDataTypeFactory = opBinding.getTypeFactory();
      return relDataTypeFactory.createSqlType(typeToTransform.getSqlTypeName());
    }
  };
}
