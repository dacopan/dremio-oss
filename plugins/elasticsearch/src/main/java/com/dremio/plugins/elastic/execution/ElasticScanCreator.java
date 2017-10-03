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
package com.dremio.plugins.elastic.execution;

import java.util.ArrayList;
import java.util.List;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.elastic.proto.ElasticReaderProto.ElasticTableXattr;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.vector.complex.fn.WorkingBuffer;
import com.dremio.plugins.elastic.ElasticConnectionPool.ElasticConnection;
import com.dremio.plugins.elastic.ElasticsearchStoragePlugin2;
import com.dremio.plugins.elastic.mapping.FieldAnnotation;
import com.dremio.plugins.elastic.planning.ElasticsearchScanSpec;
import com.dremio.plugins.elastic.planning.ElasticsearchSubScan;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.op.scan.ScanOperator;
import com.dremio.sabot.op.spi.ProducerOperator;
import com.dremio.service.namespace.dataset.proto.Affinity;
import com.dremio.service.namespace.dataset.proto.DatasetSplit;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Creates a scan batch of Elastic readers.
 */
public class ElasticScanCreator implements ProducerOperator.Creator<ElasticsearchSubScan> {

  @Override
  public ProducerOperator create(FragmentExecutionContext fec, OperatorContext context, ElasticsearchSubScan subScan) throws ExecutionSetupException {
    try {

      final ElasticsearchStoragePlugin2 plugin = (ElasticsearchStoragePlugin2) fec.getStoragePlugin(subScan.getPluginId());
      List<RecordReader> readers = new ArrayList<>();
      ElasticsearchScanSpec spec = subScan.getSpec();
      ElasticTableXattr tableAttributes = ElasticTableXattr.parseFrom(subScan.getExtendedProperty().toByteArray());
      final WorkingBuffer workingBuffer = new WorkingBuffer(context.getManagedBuffer());
      final boolean useEdgeProject = context.getOptions().getOption(ExecConstants.ELASTIC_RULES_EDGE_PROJECT);
      final ImmutableMap<SchemaPath, FieldAnnotation> annotations = FieldAnnotation.getAnnotationMap(tableAttributes.getAnnotationList());
      final FieldReadDefinition readDefinition = FieldReadDefinition.getTree(subScan.getSchema(), annotations, workingBuffer);

      for (DatasetSplit split : subScan.getSplits()) {

        final ElasticConnection connection = plugin.getConnection(FluentIterable.from(split.getAffinitiesList()).transform(new Function<Affinity, String>(){
          @Override
          public String apply(Affinity input) {
            return input.getHost();
          }}));

        readers.add(new ElasticsearchRecordReader(
            plugin,
            subScan.getTableSchemaPath(),
            tableAttributes,
            context,
            spec,
            useEdgeProject,
            split,
            connection,
            subScan.getColumns(),
            readDefinition,
            plugin.getConfig()
            ));
      }

      return new ScanOperator(fec.getSchemaUpdater(), subScan, context, readers.iterator());
    } catch (InvalidProtocolBufferException e) {
      throw new ExecutionSetupException(e);
    }
  }
}
