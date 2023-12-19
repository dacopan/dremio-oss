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
package com.dremio.exec.planner.sql.handlers;

import static com.dremio.exec.ops.ViewExpansionContext.DefaultReflectionHintBehavior.PLAN_CONTAINS_DISALLOWED_DRR;
import static com.dremio.exec.ops.ViewExpansionContext.DefaultReflectionHintBehavior.SUCCESS;
import static com.dremio.exec.planner.sql.handlers.RelTransformer.NO_OP_TRANSFORMER;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.catalog.model.dataset.TableVersionType;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.ops.ViewExpansionContext;
import com.dremio.exec.planner.DremioVolcanoPlanner;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.StatelessRelShuttleImpl;
import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.logical.PreProcessRel;
import com.dremio.exec.planner.logical.ValuesRewriteShuttle;
import com.dremio.exec.planner.normalizer.RelNormalizerTransformer;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.planner.sql.SqlValidatorAndToRelContext;
import com.dremio.exec.planner.sql.UnsupportedQueryPlanVisitor;
import com.dremio.exec.planner.sql.parser.DmlUtils;
import com.dremio.exec.planner.sql.parser.DremioHint;
import com.dremio.exec.planner.sql.parser.SqlDmlOperator;
import com.dremio.exec.planner.sql.parser.UnsupportedOperatorsVisitor;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.work.foreman.ForemanSetupException;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.dremio.options.OptionResolver;
import com.dremio.options.OptionValue;
import com.dremio.sabot.exec.context.ContextInformation;
import com.dremio.service.Pointer;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;

public class SqlToRelTransformer {
  public static final Logger LOGGER = LoggerFactory.getLogger(SqlToRelTransformer.class);

  public static RelNode preprocessNode(RelNode rel) throws SqlUnsupportedException {
    /*
     * Traverse the tree to do the following pre-processing tasks:
     *
     * 1) Replace the convert_from, convert_to function to
     * actual implementations Eg: convert_from(EXPR, 'JSON') be converted to convert_fromjson(EXPR);
     * TODO: Ideally all function rewrites would move here instead of RexToExpr.
     *
     * 2) See where the tree contains unsupported functions; throw SqlUnsupportedException if there is any.
     *
     * 3) Rewrite LogicalValue's row type and replace any Decimal tuples with double
     * since we don't support decimal type during execution.
     * See com.dremio.exec.planner.logical.ValuesRel.writeLiteral where we write Decimal as Double.
     * See com.dremio.exec.vector.complex.fn.VectorOutput.innerRun where we throw exception for Decimal type.
     */

    PreProcessRel visitor = PreProcessRel.createVisitor(rel.getCluster().getRexBuilder());
    try {
      rel = rel.accept(visitor);
    } catch (UnsupportedOperationException ex) {
      visitor.convertException();
      throw ex;
    }

    rel = rel.accept(new ValuesRewriteShuttle());

    return rel;
  }

  public static ConvertedRelNode validateAndConvert(
      SqlHandlerConfig config,
      SqlNode sqlNode) throws ForemanSetupException, RelConversionException, ValidationException {
    return validateAndConvert(config, sqlNode, createSqlValidatorAndToRelContext(config), NO_OP_TRANSFORMER);
  }

  public static ConvertedRelNode validateAndConvertForDml(
    SqlHandlerConfig config,
    SqlNode sqlNode,
    String sourceName) throws ForemanSetupException, RelConversionException, ValidationException {
    SqlValidatorAndToRelContext.Builder builder =
      SqlValidatorAndToRelContext.builder(config.getConverter())
        .requireSubqueryExpansion(); // This is required because generate rex sub-queries does not work update/delete/insert
    // We do not need to build with version context if we do not have version context in our sql.
    if (sqlNode instanceof SqlDmlOperator) {
      SqlDmlOperator sqlDmlOperator = (SqlDmlOperator) sqlNode;
      TableVersionContext versionContextFromSql = DmlUtils.getVersionContext(sqlDmlOperator);
      if (versionContextFromSql != TableVersionContext.NOT_SPECIFIED && versionContextFromSql.getType() != TableVersionType.NOT_SPECIFIED) {
        builder = builder.withVersionContext(sourceName, versionContextFromSql);
      }
    }
    return validateAndConvert(config, sqlNode, builder.build(), NO_OP_TRANSFORMER);
  }

  public static ConvertedRelNode validateAndConvert(
    SqlHandlerConfig config,
    SqlNode sqlNode,
    RelTransformer relTransformer) throws ForemanSetupException, RelConversionException, ValidationException {
    return validateAndConvert(config, sqlNode, createSqlValidatorAndToRelContext(config), relTransformer);
  }

  public static ConvertedRelNode validateAndConvert(
      SqlHandlerConfig config,
      SqlNode sqlNode,
      SqlValidatorAndToRelContext sqlValidatorAndToRelContext,
      RelTransformer relTransformer) throws ForemanSetupException, RelConversionException, ValidationException {
    config.getConverter().getSubstitutionProvider().setPostSubstitutionTransformer( // For DRR
      PlannerUtil.getPostSubstitutionTransformer(config, PlannerPhase.POST_SUBSTITUTION));
    config.getConverter().getSubstitutionProvider().setObserver(config.getObserver());


    final Pair<SqlNode, RelDataType> validatedTypedSqlNode = validateNode(config, sqlValidatorAndToRelContext, sqlNode);

    config.getObserver().beginState(AttemptObserver.toEvent(UserBitShared.AttemptEvent.State.PLANNING));

    final RelNode relRaw = convertSqlToRel(config, sqlValidatorAndToRelContext, validatedTypedSqlNode.getKey());
    UnsupportedQueryPlanVisitor.checkForUnsupportedQueryPlan(relRaw);
    final RelNode relRawAfterHints = processReflectionHints(config, sqlValidatorAndToRelContext, relRaw, validatedTypedSqlNode.getKey());
    final RelNode rel = preprocessTransform(config, relRawAfterHints, relTransformer);

    return new ConvertedRelNode(rel, validatedTypedSqlNode.getValue());
  }

  private static SqlValidatorAndToRelContext createSqlValidatorAndToRelContext(SqlHandlerConfig config) {
    return SqlValidatorAndToRelContext.builder(config.getConverter())
      .build();
  }

  private static Pair<SqlNode, RelDataType> validateNode(
      SqlHandlerConfig config,
      SqlValidatorAndToRelContext sqlValidatorAndToRelContext,
      SqlNode sqlNode) throws ValidationException, ForemanSetupException {
    final Stopwatch stopwatch = Stopwatch.createStarted();
    final SqlNode sqlNodeValidated;

    try {
      sqlNodeValidated = sqlValidatorAndToRelContext.validate(sqlNode);
    } catch (final Throwable ex) {
      throw new ValidationException("unable to validate sql node", ex);
    }
    final Pair<SqlNode, RelDataType> typedSqlNode = new Pair<>(sqlNodeValidated, sqlValidatorAndToRelContext.getOutputType(sqlNodeValidated));

    // Check if the unsupported functionality is used
    UnsupportedOperatorsVisitor visitor = UnsupportedOperatorsVisitor.createVisitor(config.getContext());
    try {
      sqlNodeValidated.accept(visitor);
    } catch (UnsupportedOperationException ex) {
      // If the exception due to the unsupported functionalities
      visitor.convertException();

      // If it is not, let this exception move forward to higher logic
      throw ex;
    }

    config.getObserver().planValidated(typedSqlNode.getValue(), typedSqlNode.getKey(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
    return typedSqlNode;
  }


  private static RelNode convertSqlToRel(
      SqlHandlerConfig config,
      SqlValidatorAndToRelContext sqlValidatorAndToRelContext,
      SqlNode validatedNode) {
    final ContextInformation contextInformation = config.getConverter().getFunctionContext().getContextInformation();
    final AttemptObserver observer = config.getObserver();
    final OptionResolver optionResolver = config.getContext().getOptions();

    final Stopwatch stopwatch = Stopwatch.createStarted();

    final SqlConverter.RelRootPlus convertible =
      sqlValidatorAndToRelContext.toConvertibleRelRoot(validatedNode, true);
    contextInformation.setPlanCacheable(contextInformation.isPlanCacheable() && convertible.isPlanCacheable());
    observer.planConvertedToRel(convertible.rel, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    return convertible.rel;
  }

  private static RelNode preprocessTransform(
      SqlHandlerConfig config,
      RelNode relNode,
      RelTransformer relTransformer) throws SqlUnsupportedException {
    final Catalog catalog = config.getContext().getCatalog();
    final AttemptObserver observer = config.getObserver();
    final RelNormalizerTransformer relNormalizerTransformer = config.getRelNormalizerTransformer();

    try {
      final RelNode normalizedRel = relNormalizerTransformer.transformPreSerialization(relNode);

      final RelNode transformed = relTransformer.transform(normalizedRel);

      observer.planSerializable(transformed);
      updateOriginalRoot(transformed);

      final RelNode expansionNodesRemoved = ExpansionNode.removeFromTree(transformed);
      final RelNode normalizedRel2 = relNormalizerTransformer.transformPostSerialization(expansionNodesRemoved);

      PlanLogUtil.log("INITIAL", normalizedRel2, LOGGER, null);
      observer.setNumJoinsInUserQuery(MoreRelOptUtil.countJoins(normalizedRel2));

      return normalizedRel2;
    } finally {
      observer.tablesCollected(catalog.getAllRequestedTables());
    }
  }

  private static void updateOriginalRoot(RelNode relNode) {
    final DremioVolcanoPlanner volcanoPlanner = (DremioVolcanoPlanner) relNode.getCluster().getPlanner();
    volcanoPlanner.setOriginalRoot(relNode);
  }

  private static RelNode processReflectionHints(SqlHandlerConfig config,
                                                SqlValidatorAndToRelContext sqlValidatorAndToRelContext,
                                                RelNode relRaw,
                                                SqlNode validatedTypedSqlNode) {
    Set<String> includeReflections = new HashSet<>();
    Set<String> excludeReflections = new HashSet<>();
    Set<String> chooseIfMatched = new HashSet<>();
    Pointer<Optional<Boolean>> noReflections = new Pointer<>(Optional.empty());
    relRaw.accept(new StatelessRelShuttleImpl() {
      private int depth = 0;
      @Override
      public RelNode visit(LogicalProject project) {
        if (!includeReflections.isEmpty() || !excludeReflections.isEmpty() || !chooseIfMatched.isEmpty() || noReflections.value.isPresent()) {
          return project; // Once hints are found, don't recursively look for more
        }
        project.getHints().forEach(hint -> {
          if (hint.hintName.equalsIgnoreCase(DremioHint.CONSIDER_REFLECTIONS.getHintName())) {
            includeReflections.addAll(hint.listOptions);
          } else if (hint.hintName.equalsIgnoreCase(DremioHint.EXCLUDE_REFLECTIONS.getHintName())) {
            excludeReflections.addAll(hint.listOptions);
          } else if (hint.hintName.equalsIgnoreCase(DremioHint.CHOOSE_REFLECTIONS.getHintName())) {
            chooseIfMatched.addAll(hint.listOptions);
          } else if (hint.hintName.equalsIgnoreCase(DremioHint.NO_REFLECTIONS.getHintName())) {
            if (hint.listOptions.size() == 1 && "false".equalsIgnoreCase(hint.listOptions.get(0))) {
              noReflections.value = Optional.of(false);
            } else {
              noReflections.value = Optional.of(true);
            }
          }
        });
        return super.visit(project);
      }
      @Override
      public RelNode visit(RelNode other) {
        // Allow hints from either query or first top-level view
        if (other instanceof ExpansionNode) {
          if (depth == 1) {
            return other;
          }
          try {
            depth++;
            return super.visit(other);
          } finally {
            depth--;
          }
        }
        return this.visitChildren(other);
      }
    });
    if (!includeReflections.isEmpty()) {
      config.getContext().getOptions().setOption(OptionValue.createString(OptionValue.OptionType.QUERY,
        DremioHint.CONSIDER_REFLECTIONS.getOption().getOptionName(), String.join(",", includeReflections)));
    }
    if (!excludeReflections.isEmpty()) {
      config.getContext().getOptions().setOption(OptionValue.createString(OptionValue.OptionType.QUERY,
        DremioHint.EXCLUDE_REFLECTIONS.getOption().getOptionName(), String.join(",", excludeReflections)));
    }
    if (!chooseIfMatched.isEmpty()) {
      config.getContext().getOptions().setOption(OptionValue.createString(OptionValue.OptionType.QUERY,
        DremioHint.CHOOSE_REFLECTIONS.getOption().getOptionName(), String.join(",", chooseIfMatched)));
    }
    if (noReflections.value.isPresent()) {
      config.getContext().getOptions().setOption(OptionValue.createBoolean(OptionValue.OptionType.QUERY,
        DremioHint.NO_REFLECTIONS.getOption().getOptionName(), noReflections.value.get()));
    }

    final RelNode relRawAfterHints;
    final ViewExpansionContext vec = config.getConverter().getViewExpansionContext();
    if (vec.reportDefaultMaterializations(config.getObserver(), relRaw, config.getMaterializations()) == PLAN_CONTAINS_DISALLOWED_DRR) {
      // Reflection hints specified at query level are only known after convert sql to rel.  If a DRR ends up in the rel
      // plan that is disallowed by a query level hint, then we need to re-convert.  Note that since we set the query
      // level hints back into the query level options, the second re-convert will see these options and not use the DRR.
      config.getConverter().getPlannerCatalog().clearConvertedCache();
      relRawAfterHints = convertSqlToRel(config, sqlValidatorAndToRelContext, validatedTypedSqlNode);
      Preconditions.checkState(vec.reportDefaultMaterializations(config.getObserver(), relRawAfterHints, config.getMaterializations()) == SUCCESS,
        "Unable to apply query level reflection hints on default raw reflections"); // Should never happen
    } else {
      relRawAfterHints = relRaw;
    }
    return relRawAfterHints;
  }
}
