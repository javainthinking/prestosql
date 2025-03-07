/*
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
package io.prestosql.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.prestosql.cost.CostCalculator;
import io.prestosql.cost.CostCalculator.EstimatedExchanges;
import io.prestosql.cost.CostComparator;
import io.prestosql.cost.StatsCalculator;
import io.prestosql.cost.TaskCountEstimator;
import io.prestosql.execution.TaskManagerConfig;
import io.prestosql.execution.scheduler.NodeSchedulerConfig;
import io.prestosql.metadata.InternalNodeManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.split.PageSourceManager;
import io.prestosql.split.SplitManager;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.sql.parser.SqlParser;
import io.prestosql.sql.planner.iterative.IterativeOptimizer;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.iterative.rule.AddExchangesBelowPartialAggregationOverGroupIdRuleSet;
import io.prestosql.sql.planner.iterative.rule.AddIntermediateAggregations;
import io.prestosql.sql.planner.iterative.rule.CanonicalizeExpressions;
import io.prestosql.sql.planner.iterative.rule.CreatePartialTopN;
import io.prestosql.sql.planner.iterative.rule.DesugarAtTimeZone;
import io.prestosql.sql.planner.iterative.rule.DesugarCurrentPath;
import io.prestosql.sql.planner.iterative.rule.DesugarCurrentUser;
import io.prestosql.sql.planner.iterative.rule.DesugarLambdaExpression;
import io.prestosql.sql.planner.iterative.rule.DesugarTryExpression;
import io.prestosql.sql.planner.iterative.rule.DetermineJoinDistributionType;
import io.prestosql.sql.planner.iterative.rule.DetermineSemiJoinDistributionType;
import io.prestosql.sql.planner.iterative.rule.EliminateCrossJoins;
import io.prestosql.sql.planner.iterative.rule.EvaluateZeroLimit;
import io.prestosql.sql.planner.iterative.rule.EvaluateZeroSample;
import io.prestosql.sql.planner.iterative.rule.ExtractSpatialJoins;
import io.prestosql.sql.planner.iterative.rule.GatherAndMergeWindows;
import io.prestosql.sql.planner.iterative.rule.ImplementBernoulliSampleAsFilter;
import io.prestosql.sql.planner.iterative.rule.ImplementFilteredAggregations;
import io.prestosql.sql.planner.iterative.rule.InlineProjections;
import io.prestosql.sql.planner.iterative.rule.MergeFilters;
import io.prestosql.sql.planner.iterative.rule.MergeLimitWithDistinct;
import io.prestosql.sql.planner.iterative.rule.MergeLimitWithSort;
import io.prestosql.sql.planner.iterative.rule.MergeLimitWithTopN;
import io.prestosql.sql.planner.iterative.rule.MergeLimits;
import io.prestosql.sql.planner.iterative.rule.MultipleDistinctAggregationToMarkDistinct;
import io.prestosql.sql.planner.iterative.rule.PickTableLayout;
import io.prestosql.sql.planner.iterative.rule.PruneAggregationColumns;
import io.prestosql.sql.planner.iterative.rule.PruneAggregationSourceColumns;
import io.prestosql.sql.planner.iterative.rule.PruneCountAggregationOverScalar;
import io.prestosql.sql.planner.iterative.rule.PruneCrossJoinColumns;
import io.prestosql.sql.planner.iterative.rule.PruneFilterColumns;
import io.prestosql.sql.planner.iterative.rule.PruneIndexSourceColumns;
import io.prestosql.sql.planner.iterative.rule.PruneJoinChildrenColumns;
import io.prestosql.sql.planner.iterative.rule.PruneJoinColumns;
import io.prestosql.sql.planner.iterative.rule.PruneLimitColumns;
import io.prestosql.sql.planner.iterative.rule.PruneMarkDistinctColumns;
import io.prestosql.sql.planner.iterative.rule.PruneOrderByInAggregation;
import io.prestosql.sql.planner.iterative.rule.PruneOutputColumns;
import io.prestosql.sql.planner.iterative.rule.PruneProjectColumns;
import io.prestosql.sql.planner.iterative.rule.PruneSemiJoinColumns;
import io.prestosql.sql.planner.iterative.rule.PruneSemiJoinFilteringSourceColumns;
import io.prestosql.sql.planner.iterative.rule.PruneTableScanColumns;
import io.prestosql.sql.planner.iterative.rule.PruneTopNColumns;
import io.prestosql.sql.planner.iterative.rule.PruneValuesColumns;
import io.prestosql.sql.planner.iterative.rule.PruneWindowColumns;
import io.prestosql.sql.planner.iterative.rule.PushAggregationThroughOuterJoin;
import io.prestosql.sql.planner.iterative.rule.PushLimitThroughMarkDistinct;
import io.prestosql.sql.planner.iterative.rule.PushLimitThroughProject;
import io.prestosql.sql.planner.iterative.rule.PushLimitThroughSemiJoin;
import io.prestosql.sql.planner.iterative.rule.PushPartialAggregationThroughExchange;
import io.prestosql.sql.planner.iterative.rule.PushPartialAggregationThroughJoin;
import io.prestosql.sql.planner.iterative.rule.PushProjectionThroughExchange;
import io.prestosql.sql.planner.iterative.rule.PushProjectionThroughUnion;
import io.prestosql.sql.planner.iterative.rule.PushRemoteExchangeThroughAssignUniqueId;
import io.prestosql.sql.planner.iterative.rule.PushTableWriteThroughUnion;
import io.prestosql.sql.planner.iterative.rule.PushTopNThroughUnion;
import io.prestosql.sql.planner.iterative.rule.RemoveEmptyDelete;
import io.prestosql.sql.planner.iterative.rule.RemoveFullSample;
import io.prestosql.sql.planner.iterative.rule.RemoveRedundantIdentityProjections;
import io.prestosql.sql.planner.iterative.rule.RemoveTrivialFilters;
import io.prestosql.sql.planner.iterative.rule.RemoveUnreferencedScalarApplyNodes;
import io.prestosql.sql.planner.iterative.rule.RemoveUnreferencedScalarLateralNodes;
import io.prestosql.sql.planner.iterative.rule.ReorderJoins;
import io.prestosql.sql.planner.iterative.rule.RewriteSpatialPartitioningAggregation;
import io.prestosql.sql.planner.iterative.rule.SimplifyCountOverConstant;
import io.prestosql.sql.planner.iterative.rule.SimplifyExpressions;
import io.prestosql.sql.planner.iterative.rule.SingleDistinctAggregationToGroupBy;
import io.prestosql.sql.planner.iterative.rule.TransformCorrelatedInPredicateToJoin;
import io.prestosql.sql.planner.iterative.rule.TransformCorrelatedLateralJoinToJoin;
import io.prestosql.sql.planner.iterative.rule.TransformCorrelatedScalarAggregationToJoin;
import io.prestosql.sql.planner.iterative.rule.TransformCorrelatedScalarSubquery;
import io.prestosql.sql.planner.iterative.rule.TransformCorrelatedSingleRowSubqueryToProject;
import io.prestosql.sql.planner.iterative.rule.TransformExistsApplyToLateralNode;
import io.prestosql.sql.planner.iterative.rule.TransformUncorrelatedInPredicateSubqueryToSemiJoin;
import io.prestosql.sql.planner.iterative.rule.TransformUncorrelatedLateralToJoin;
import io.prestosql.sql.planner.optimizations.AddExchanges;
import io.prestosql.sql.planner.optimizations.AddLocalExchanges;
import io.prestosql.sql.planner.optimizations.BeginTableWrite;
import io.prestosql.sql.planner.optimizations.CheckSubqueryNodesAreRewritten;
import io.prestosql.sql.planner.optimizations.HashGenerationOptimizer;
import io.prestosql.sql.planner.optimizations.ImplementIntersectAndExceptAsUnion;
import io.prestosql.sql.planner.optimizations.IndexJoinOptimizer;
import io.prestosql.sql.planner.optimizations.LimitPushDown;
import io.prestosql.sql.planner.optimizations.MetadataDeleteOptimizer;
import io.prestosql.sql.planner.optimizations.MetadataQueryOptimizer;
import io.prestosql.sql.planner.optimizations.OptimizeMixedDistinctAggregations;
import io.prestosql.sql.planner.optimizations.PlanOptimizer;
import io.prestosql.sql.planner.optimizations.PredicatePushDown;
import io.prestosql.sql.planner.optimizations.PruneUnreferencedOutputs;
import io.prestosql.sql.planner.optimizations.ReplicateSemiJoinInDelete;
import io.prestosql.sql.planner.optimizations.SetFlatteningOptimizer;
import io.prestosql.sql.planner.optimizations.StatsRecordingPlanOptimizer;
import io.prestosql.sql.planner.optimizations.TransformQuantifiedComparisonApplyToLateralJoin;
import io.prestosql.sql.planner.optimizations.UnaliasSymbolReferences;
import io.prestosql.sql.planner.optimizations.WindowFilterPushDown;
import org.weakref.jmx.MBeanExporter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.util.List;
import java.util.Set;

public class PlanOptimizers
{
    private final List<PlanOptimizer> optimizers;
    private final RuleStatsRecorder ruleStats = new RuleStatsRecorder();
    private final OptimizerStatsRecorder optimizerStats = new OptimizerStatsRecorder();
    private final MBeanExporter exporter;

    @Inject
    public PlanOptimizers(
            Metadata metadata,
            SqlParser sqlParser,
            FeaturesConfig featuresConfig,
            NodeSchedulerConfig nodeSchedulerConfig,
            InternalNodeManager nodeManager,
            TaskManagerConfig taskManagerConfig,
            MBeanExporter exporter,
            SplitManager splitManager,
            PageSourceManager pageSourceManager,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            @EstimatedExchanges CostCalculator estimatedExchangesCostCalculator,
            CostComparator costComparator,
            TaskCountEstimator taskCountEstimator)
    {
        this(metadata,
                sqlParser,
                featuresConfig,
                taskManagerConfig,
                false,
                exporter,
                splitManager,
                pageSourceManager,
                statsCalculator,
                costCalculator,
                estimatedExchangesCostCalculator,
                costComparator,
                taskCountEstimator);
    }

    @PostConstruct
    public void initialize()
    {
        ruleStats.export(exporter);
        optimizerStats.export(exporter);
    }

    @PreDestroy
    public void destroy()
    {
        ruleStats.unexport(exporter);
        optimizerStats.unexport(exporter);
    }

    public PlanOptimizers(
            Metadata metadata,
            SqlParser sqlParser,
            FeaturesConfig featuresConfig,
            TaskManagerConfig taskManagerConfig,
            boolean forceSingleNode,
            MBeanExporter exporter,
            SplitManager splitManager,
            PageSourceManager pageSourceManager,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            CostCalculator estimatedExchangesCostCalculator,
            CostComparator costComparator,
            TaskCountEstimator taskCountEstimator)
    {
        this.exporter = exporter;
        ImmutableList.Builder<PlanOptimizer> builder = ImmutableList.builder();

        Set<Rule<?>> predicatePushDownRules = ImmutableSet.of(
                new MergeFilters());

        // TODO: Once we've migrated handling all the plan node types, replace uses of PruneUnreferencedOutputs with an IterativeOptimizer containing these rules.
        Set<Rule<?>> columnPruningRules = ImmutableSet.of(
                new PruneAggregationColumns(),
                new PruneAggregationSourceColumns(),
                new PruneCrossJoinColumns(),
                new PruneFilterColumns(),
                new PruneIndexSourceColumns(),
                new PruneJoinChildrenColumns(),
                new PruneJoinColumns(),
                new PruneMarkDistinctColumns(),
                new PruneOutputColumns(),
                new PruneProjectColumns(),
                new PruneSemiJoinColumns(),
                new PruneSemiJoinFilteringSourceColumns(),
                new PruneTopNColumns(),
                new PruneValuesColumns(),
                new PruneWindowColumns(),
                new PruneLimitColumns(),
                new PruneTableScanColumns());

        IterativeOptimizer inlineProjections = new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                estimatedExchangesCostCalculator,
                ImmutableSet.of(
                        new InlineProjections(),
                        new RemoveRedundantIdentityProjections()));

        IterativeOptimizer projectionPushDown = new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                estimatedExchangesCostCalculator,
                ImmutableSet.of(
                        new PushProjectionThroughUnion(),
                        new PushProjectionThroughExchange()));

        IterativeOptimizer simplifyOptimizer = new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                estimatedExchangesCostCalculator,
                new SimplifyExpressions(metadata, sqlParser).rules());

        PlanOptimizer predicatePushDown = new StatsRecordingPlanOptimizer(optimizerStats, new PredicatePushDown(metadata, sqlParser));

        builder.add(
                // Clean up all the sugar in expressions, e.g. AtTimeZone, must be run before all the other optimizers
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.<Rule<?>>builder()
                                .addAll(new DesugarLambdaExpression().rules())
                                .addAll(new DesugarAtTimeZone(metadata, sqlParser).rules())
                                .addAll(new DesugarCurrentUser().rules())
                                .addAll(new DesugarCurrentPath().rules())
                                .addAll(new DesugarTryExpression().rules())
                                .build()),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        new CanonicalizeExpressions().rules()),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.<Rule<?>>builder()
                                .addAll(predicatePushDownRules)
                                .addAll(columnPruningRules)
                                .addAll(ImmutableSet.of(
                                        new RemoveRedundantIdentityProjections(),
                                        new RemoveFullSample(),
                                        new EvaluateZeroLimit(),
                                        new EvaluateZeroSample(),
                                        new PushLimitThroughProject(),
                                        new MergeLimits(),
                                        new MergeLimitWithSort(),
                                        new MergeLimitWithTopN(),
                                        new PushLimitThroughMarkDistinct(),
                                        new PushLimitThroughSemiJoin(),
                                        new RemoveTrivialFilters(),
                                        new ImplementFilteredAggregations(),
                                        new SingleDistinctAggregationToGroupBy(),
                                        new MultipleDistinctAggregationToMarkDistinct(),
                                        new ImplementBernoulliSampleAsFilter(),
                                        new MergeLimitWithDistinct(),
                                        new PruneCountAggregationOverScalar(),
                                        new PruneOrderByInAggregation(metadata.getFunctionRegistry()),
                                        new RewriteSpatialPartitioningAggregation(metadata)))
                                .build()),
                simplifyOptimizer,
                new UnaliasSymbolReferences(),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new RemoveRedundantIdentityProjections())),
                new SetFlatteningOptimizer(),
                new ImplementIntersectAndExceptAsUnion(),
                new LimitPushDown(), // Run the LimitPushDown after flattening set operators to make it easier to do the set flattening
                new PruneUnreferencedOutputs(),
                inlineProjections,
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        columnPruningRules),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new TransformExistsApplyToLateralNode(metadata.getFunctionRegistry()))),
                new TransformQuantifiedComparisonApplyToLateralJoin(metadata),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(
                                new RemoveUnreferencedScalarLateralNodes(),
                                new TransformUncorrelatedLateralToJoin(),
                                new TransformUncorrelatedInPredicateSubqueryToSemiJoin(),
                                new TransformCorrelatedScalarAggregationToJoin(metadata.getFunctionRegistry()),
                                new TransformCorrelatedLateralJoinToJoin())),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(
                                new RemoveUnreferencedScalarApplyNodes(),
                                new TransformCorrelatedInPredicateToJoin(), // must be run after PruneUnreferencedOutputs
                                new TransformCorrelatedScalarSubquery(), // must be run after TransformCorrelatedScalarAggregationToJoin
                                new TransformCorrelatedLateralJoinToJoin(),
                                new ImplementFilteredAggregations())),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(
                                new InlineProjections(),
                                new RemoveRedundantIdentityProjections(),
                                new TransformCorrelatedSingleRowSubqueryToProject())),
                new CheckSubqueryNodesAreRewritten(),
                predicatePushDown,
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        new PickTableLayout(metadata, sqlParser).rules()),
                new PruneUnreferencedOutputs(),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(
                                new RemoveRedundantIdentityProjections(),
                                new PushAggregationThroughOuterJoin())),
                inlineProjections,
                simplifyOptimizer, // Re-run the SimplifyExpressions to simplify any recomposed expressions from other optimizations
                projectionPushDown,
                new UnaliasSymbolReferences(), // Run again because predicate pushdown and projection pushdown might add more projections
                new PruneUnreferencedOutputs(), // Make sure to run this before index join. Filtered projections may not have all the columns.
                new IndexJoinOptimizer(metadata), // Run this after projections and filters have been fully simplified and pushed down
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new SimplifyCountOverConstant())),
                new LimitPushDown(), // Run LimitPushDown before WindowFilterPushDown
                new WindowFilterPushDown(metadata), // This must run after PredicatePushDown and LimitPushDown so that it squashes any successive filter nodes and limits
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.<Rule<?>>builder()
                                // add UnaliasSymbolReferences when it's ported
                                .add(new RemoveRedundantIdentityProjections())
                                .addAll(GatherAndMergeWindows.rules())
                                .build()),
                inlineProjections,
                new PruneUnreferencedOutputs(), // Make sure to run this at the end to help clean the plan for logging/execution and not remove info that other optimizers might need at an earlier point
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new RemoveRedundantIdentityProjections())),
                new MetadataQueryOptimizer(metadata),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new EliminateCrossJoins())), // This can pull up Filter and Project nodes from between Joins, so we need to push them down again
                predicatePushDown,
                simplifyOptimizer, // Should be always run after PredicatePushDown
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        new PickTableLayout(metadata, sqlParser).rules()),
                projectionPushDown,
                new PruneUnreferencedOutputs(),
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new RemoveRedundantIdentityProjections())),

                // Because ReorderJoins runs only once,
                // PredicatePushDown, PruneUnreferenedOutputpus and RemoveRedundantIdentityProjections
                // need to run beforehand in order to produce an optimal join order
                // It also needs to run after EliminateCrossJoins so that its chosen order doesn't get undone.
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        estimatedExchangesCostCalculator,
                        ImmutableSet.of(new ReorderJoins(costComparator))));

        builder.add(new OptimizeMixedDistinctAggregations(metadata));
        builder.add(new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                estimatedExchangesCostCalculator,
                ImmutableSet.of(
                        new CreatePartialTopN(),
                        new PushTopNThroughUnion())));
        builder.add(new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                costCalculator,
                ImmutableSet.<Rule<?>>builder()
                        .add(new RemoveRedundantIdentityProjections())
                        .addAll(new ExtractSpatialJoins(metadata, splitManager, pageSourceManager, sqlParser).rules())
                        .add(new InlineProjections())
                        .build()));

        if (!forceSingleNode) {
            builder.add(new ReplicateSemiJoinInDelete()); // Must run before AddExchanges
            builder.add((new IterativeOptimizer(
                    ruleStats,
                    statsCalculator,
                    estimatedExchangesCostCalculator,
                    ImmutableSet.of(
                            new DetermineJoinDistributionType(costComparator, taskCountEstimator), // Must run before AddExchanges
                            // Must run before AddExchanges and after ReplicateSemiJoinInDelete
                            // to avoid temporarily having an invalid plan
                            new DetermineSemiJoinDistributionType(costComparator, taskCountEstimator)))));
            builder.add(
                    new IterativeOptimizer(
                            ruleStats,
                            statsCalculator,
                            estimatedExchangesCostCalculator,
                            ImmutableSet.of(new PushTableWriteThroughUnion()))); // Must run before AddExchanges
            builder.add(new StatsRecordingPlanOptimizer(optimizerStats, new AddExchanges(metadata, sqlParser)));
        }
        //noinspection UnusedAssignment
        estimatedExchangesCostCalculator = null; // Prevent accidental use after AddExchanges

        builder.add(
                new IterativeOptimizer(
                        ruleStats,
                        statsCalculator,
                        costCalculator,
                        ImmutableSet.of(new RemoveEmptyDelete()))); // Run RemoveEmptyDelete after table scan is removed by PickTableLayout/AddExchanges

        builder.add(predicatePushDown); // Run predicate push down one more time in case we can leverage new information from layouts' effective predicate
        builder.add(simplifyOptimizer); // Should be always run after PredicatePushDown
        builder.add(projectionPushDown);
        builder.add(inlineProjections);
        builder.add(new UnaliasSymbolReferences()); // Run unalias after merging projections to simplify projections more efficiently
        builder.add(new PruneUnreferencedOutputs());

        builder.add(new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                costCalculator,
                ImmutableSet.<Rule<?>>builder()
                        .add(new RemoveRedundantIdentityProjections())
                        .add(new PushRemoteExchangeThroughAssignUniqueId())
                        .add(new InlineProjections())
                        .build()));

        // Optimizers above this don't understand local exchanges, so be careful moving this.
        builder.add(new AddLocalExchanges(metadata, sqlParser));

        // Optimizers above this do not need to care about aggregations with the type other than SINGLE
        // This optimizer must be run after all exchange-related optimizers
        builder.add(new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                costCalculator,
                ImmutableSet.of(
                        new PushPartialAggregationThroughJoin(),
                        new PushPartialAggregationThroughExchange(metadata.getFunctionRegistry()),
                        new PruneJoinColumns())));
        builder.add(new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                costCalculator,
                new AddExchangesBelowPartialAggregationOverGroupIdRuleSet(metadata, sqlParser, taskCountEstimator, taskManagerConfig).rules()));
        builder.add(new IterativeOptimizer(
                ruleStats,
                statsCalculator,
                costCalculator,
                ImmutableSet.of(
                        new AddIntermediateAggregations(),
                        new RemoveRedundantIdentityProjections())));
        // DO NOT add optimizers that change the plan shape (computations) after this point

        // Precomputed hashes - this assumes that partitioning will not change
        builder.add(new HashGenerationOptimizer());

        builder.add(new MetadataDeleteOptimizer(metadata));
        builder.add(new BeginTableWrite(metadata)); // HACK! see comments in BeginTableWrite

        // TODO: consider adding a formal final plan sanitization optimizer that prepares the plan for transmission/execution/logging
        // TODO: figure out how to improve the set flattening optimizer so that it can run at any point

        this.optimizers = builder.build();
    }

    public List<PlanOptimizer> get()
    {
        return optimizers;
    }
}
