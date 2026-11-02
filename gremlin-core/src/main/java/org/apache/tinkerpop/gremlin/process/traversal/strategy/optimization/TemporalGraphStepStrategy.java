/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GraphStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Folds {@link AllenFilterStep} directly into preceding {@link GraphStep} and {@link VertexStep} steps,
 * evaluating temporal filter conditions directly at the storage iterator level before wrapping elements into
 * {@link org.apache.tinkerpop.gremlin.process.traversal.Traverser} objects.
 *
 * @author Alexandros Spitalas
 */
public final class TemporalGraphStepStrategy
        extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy>
        implements TraversalStrategy.OptimizationStrategy {

    private static final TemporalGraphStepStrategy INSTANCE = new TemporalGraphStepStrategy();
    private static final Set<Class<? extends OptimizationStrategy>> PRIORS = new HashSet<>(Arrays.asList(
            IdentityRemovalStrategy.class,
            EarlyLimitStrategy.class,
            InlineFilterStrategy.class,
            IncidentToAdjacentStrategy.class,
            AdjacentToIncidentStrategy.class,
            ByModulatorOptimizationStrategy.class,
            FilterRankingStrategy.class,
            MatchPredicateStrategy.class,
            RepeatUnrollStrategy.class,
            CountStrategy.class,
            PathRetractionStrategy.class,
            LazyBarrierStrategy.class,
            TemporalGraphViewStrategy.class
    ));

    private TemporalGraphStepStrategy() {}

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        if (TraversalHelper.onGraphComputer(traversal))
            return;

        // Fold AllenFilterStep into preceding GraphStep
        for (final GraphStep originalGraphStep : TraversalHelper.getStepsOfClass(GraphStep.class, traversal)) {
            Step<?, ?> currentStep = originalGraphStep.getNextStep();
            while (currentStep instanceof AllenFilterStep || currentStep instanceof HasStep || currentStep instanceof NoOpBarrierStep) {
                if (currentStep instanceof AllenFilterStep) {
                    originalGraphStep.addAllenFilterStep((AllenFilterStep) currentStep);
                    TraversalHelper.copyLabels(currentStep, currentStep.getPreviousStep(), false);
                    traversal.removeStep(currentStep);
                }
                currentStep = currentStep.getNextStep();
            }
        }

        // Fold AllenFilterStep into preceding VertexStep
        for (final VertexStep originalVertexStep : TraversalHelper.getStepsOfClass(VertexStep.class, traversal)) {
            Step<?, ?> currentStep = originalVertexStep.getNextStep();
            while (currentStep instanceof AllenFilterStep || currentStep instanceof HasStep || currentStep instanceof NoOpBarrierStep) {
                if (currentStep instanceof AllenFilterStep) {
                    originalVertexStep.addAllenFilterStep((AllenFilterStep) currentStep);
                    TraversalHelper.copyLabels(currentStep, currentStep.getPreviousStep(), false);
                    traversal.removeStep(currentStep);
                }
                currentStep = currentStep.getNextStep();
            }
        }
    }

    @Override
    public Set<Class<? extends OptimizationStrategy>> applyPrior() {
        return PRIORS;
    }

    public static TemporalGraphStepStrategy instance() {
        return INSTANCE;
    }
}
