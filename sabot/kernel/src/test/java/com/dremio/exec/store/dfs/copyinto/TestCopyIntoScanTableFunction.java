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
package com.dremio.exec.store.dfs.copyinto;

import static com.dremio.common.expression.CompleteType.INT;
import static com.dremio.common.expression.CompleteType.VARCHAR;
import static com.dremio.sabot.RecordSet.li;
import static com.dremio.sabot.RecordSet.r;
import static com.dremio.sabot.RecordSet.rs;
import static com.dremio.sabot.RecordSet.st;
import static org.apache.arrow.vector.types.pojo.ArrowType.Decimal.createDecimal;

import java.util.List;

import org.apache.arrow.vector.types.pojo.Field;
import org.junit.Test;

import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.config.ExtendedProperties;
import com.dremio.exec.physical.config.SimpleQueryContext;
import com.dremio.exec.physical.config.TableFunctionPOP;
import com.dremio.exec.physical.config.copyinto.CopyIntoQueryProperties;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.dfs.easy.BaseTestEasyScanTableFunction;
import com.dremio.exec.util.ColumnUtils;
import com.dremio.sabot.RecordBatchValidator;
import com.dremio.sabot.RecordSet;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.dremio.service.namespace.file.proto.FileType;
import com.google.common.collect.ImmutableList;

import io.protostuff.ByteString;

public class TestCopyIntoScanTableFunction extends BaseTestEasyScanTableFunction {

  private static final String ERROR_INPUT_BASE = "/store/text/data/";
  private static final String JSON_ERRORS = "jsonerrors/";
  private static final String CSV_ERRORS = "csverrors/";

  private static final BatchSchema OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR = new BatchSchema(ImmutableList.of(
    Field.nullable("col1", CompleteType.BIT.getType()),
    Field.nullable("col2", CompleteType.INT.getType()),
    Field.nullable("col3", CompleteType.BIGINT.getType()),
    Field.nullable("col4", CompleteType.FLOAT.getType()),
    Field.nullable("col5", CompleteType.DOUBLE.getType()),
    Field.nullable("col6", (new CompleteType(createDecimal(6, 2, null))).getType()),
    Field.nullable("col7", CompleteType.VARCHAR.getType()),
    Field.nullable(ColumnUtils.COPY_INTO_ERROR_COLUMN_NAME, CompleteType.VARCHAR.getType())));

  private static final List<SchemaPath> ALL_TYPE_COLUMNS_WITH_ERROR = ImmutableList.of(
    SchemaPath.getSimplePath("col1"),
    SchemaPath.getSimplePath("col2"),
    SchemaPath.getSimplePath("col3"),
    SchemaPath.getSimplePath("col4"),
    SchemaPath.getSimplePath("col5"),
    SchemaPath.getSimplePath("col6"),
    SchemaPath.getSimplePath("col7"),
    SchemaPath.getSimplePath(ColumnUtils.COPY_INTO_ERROR_COLUMN_NAME)
  );

  private static final BatchSchema JSON_COMPLEX_OUTPUT_SCHEMA_WITH_ERROR = new BatchSchema(ImmutableList.of(
    Field.nullable("col1", CompleteType.VARCHAR.getType()),
    CompleteType.struct(
      INT.toField("col3"),
      VARCHAR.toField("col4")).toField("col2", true),
    INT.asList().toField("col5"),
    Field.nullable(ColumnUtils.COPY_INTO_ERROR_COLUMN_NAME, VARCHAR.getType())));

  private static final List<SchemaPath> JSON_COMPLEX_WITH_ERROR = ImmutableList.of(
    SchemaPath.getSimplePath("col1"),
    SchemaPath.getSimplePath("col2"),
    SchemaPath.getSimplePath("col5"),
    SchemaPath.getSimplePath(ColumnUtils.COPY_INTO_ERROR_COLUMN_NAME)
  );

  @Test
  public void testTypeErrorJsonFileScanWithContinue() throws Exception {
    mockJsonFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + JSON_ERRORS + "typeerror." + FileType.JSON.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + JSON_ERRORS + "output_typeerror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.JSON,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testTypeErrorCSVFileScanWithContinue() throws Exception {
    mockTextFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + CSV_ERRORS + "typeerror." + FileType.CSV.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + CSV_ERRORS + "output_typeerror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.CSV,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testSyntaxErrorJsonFileScanWithContinue() throws Exception {
    mockJsonFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + JSON_ERRORS + "syntaxerror." + FileType.JSON.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + JSON_ERRORS + "output_syntaxerror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.JSON,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testNoColumnMatchJsonFileScanWithContinue() throws Exception {
    mockJsonFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + JSON_ERRORS + "nocolumnmatcherror." + FileType.JSON.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + JSON_ERRORS + "output_nocolumnmatcherror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.JSON,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testNoColumnMatchCSVFileScanWithContinue() throws Exception {
    mockTextFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + CSV_ERRORS + "nocolumnmatcherror." + FileType.CSV.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + CSV_ERRORS + "output_nocolumnmatcherror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.CSV,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testDuplicateColumnNameJsonDataFileScanWithContinue() throws Exception {
    mockJsonFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + JSON_ERRORS + "duplicatecolumnerror." + FileType.JSON.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + JSON_ERRORS + "output_duplicatecolumnerror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.JSON,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testDuplicateColumnNameCSVDataFileScanWithContinue() throws Exception {
    mockTextFormatPlugin();
    final String testFile = ERROR_INPUT_BASE + CSV_ERRORS + "duplicatecolumnerror." + FileType.CSV.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA, inputRow(testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + CSV_ERRORS + "output_duplicatecolumnerror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.CSV,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testMultipleTopLevelArraysJsonDataFileScanWithContinue() throws Exception {
    mockJsonFormatPlugin();
    final String testFile = "multipletoplevelarrayserror." + FileType.JSON.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA,
      inputRow(ERROR_INPUT_BASE + JSON_ERRORS + testFile));
    RecordSet output = outputRecordSet(ERROR_INPUT_BASE + JSON_ERRORS + "output_multipletoplevelarrayserror.csv",
      OutputRecordType.ALL_DATA_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, "#");
    validate(input, new CopyIntoRecordBatchValidator(output), ALL_TYPE_COLUMNS_WITH_ERROR, OUTPUT_SCHEMA_ALL_TYPE_WITH_ERROR, FileType.JSON,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  @Test
  public void testComplexJsonDataFileScanWithContinue() throws Exception {
    mockJsonFormatPlugin();
    final String testFile = "complexsyntaxerror." + FileType.JSON.name().toLowerCase();
    RecordSet input = rs(SystemSchemas.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA,
      inputRow(ERROR_INPUT_BASE + JSON_ERRORS + testFile));
    RecordSet.Record r1 = r("abc", st(106, "str1"), li(5,6,7), null);
    RecordSet.Record r2 = r("pqr", st(108, "str2"), li(8,9,99), null);
    RecordSet.Record r3 = r(null, null, null, "{\"queryId\":\"6357c02f-2000-474a-9136-2121c2d7ba54\",\"queryUser\":\"testUser\",\"tableName\":\"testTable\",\"filePath\":\"file:/dremio/oss/sabot/kernel/target/test-classes/store/text/data/jsonerrors/complexsyntaxerror.json\",\"formatOptions\":{\"TRIM_SPACE\":false,\"DATE_FORMAT\":null,\"TIME_FORMAT\":null,\"EMPTY_AS_NULL\":false,\"TIMESTAMP_FORMAT\":null,\"NULL_IF\":null,\"RECORD_DELIMITER\":null,\"FIELD_DELIMITER\":null,\"QUOTE_CHAR\":null,\"ESCAPE_CHAR\":null},\"recordsLoadedCount\":2,\"recordsRejectedCount\":1}");
    RecordSet output = rs(JSON_COMPLEX_OUTPUT_SCHEMA_WITH_ERROR, r1, r2, r3);
    validate(input, new CopyIntoRecordBatchValidator(output), JSON_COMPLEX_WITH_ERROR,
      JSON_COMPLEX_OUTPUT_SCHEMA_WITH_ERROR, FileType.JSON,
      getExProps(
        new CopyIntoQueryProperties(CopyIntoQueryProperties.OnErrorOption.CONTINUE, "@S3"),
        new SimpleQueryContext("testUser", "6357c02f-2000-474a-9136-2121c2d7ba54", "testTable")));
  }

  private void validate(RecordSet input, RecordBatchValidator validator, List<SchemaPath> projectedColumns, BatchSchema batchSchema,
                        FileType fileType, ByteString exProps) throws Exception {
    TableFunctionPOP pop = getPop(batchSchema, projectedColumns, fileType, exProps);
    validateSingle(pop, TableFunctionOperator.class, input, validator, BATCH_SIZE);
  }


  private TableFunctionPOP getPop(BatchSchema fullSchema, List<SchemaPath> columns, FileType fileType, ByteString
    exProps) throws Exception {
    BatchSchema projectedSchema = fullSchema.maskAndReorder(columns);
    return new TableFunctionPOP(PROPS, null, getTableFunctionConfig(fullSchema, projectedSchema, columns,
      fileType, exProps));
  }

  private ByteString getExProps(CopyIntoQueryProperties readerOptions, SimpleQueryContext queryContext) {
    ExtendedProperties exProps = new ExtendedProperties();
    exProps.setProperty(ExtendedProperties.PropertyKey.COPY_INTO_QUERY_PROPERTIES, readerOptions);
    exProps.setProperty(ExtendedProperties.PropertyKey.QUERY_CONTEXT,queryContext);
    return ExtendedProperties.Util.getByteString(exProps);
  }
}
