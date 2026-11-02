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
package org.apache.tinkerpop.gremlin.tinkergraph.process.traversal.strategy.optimization;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AllenFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.NoOpBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.tinkergraph.process.traversal.step.sideEffect.TinkerGraphStep;

import java.util.Collections;
import java.util.Set;

/**
 * Traversal optimization strategy that ensures temporal interval steps (e.g. {@code temporalOverlaps},
 * {@code temporalBefore}) are routed to interval-tree range queries over {@link org.apache.tinkerpop.gremlin.tinkergraph.structure.TemporalTinkerIndex}
 * whenever the target element's temporalIntervals property is indexed.
 *
 * @author Alexandros Spitalas
 */
public final class TemporalIndexOptimizationStrategy
        extends AbstractTraversalStrategy<TraversalStrategy.ProviderOptimizationStrategy>
        implements TraversalStrategy.ProviderOptimizationStrategy {

    private static final TemporalIndexOptimizationStrategy INSTANCE = new TemporalIndexOptimizationStrategy();

    private TemporalIndexOptimizationStrategy() {}

    @Override
    public Set<Class<? extends ProviderOptimizationStrategy>> applyPrior() {
        return Collections.singleton(TinkerGraphStepStrategy.class);
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        if (TraversalHelper.onGraphComputer(traversal))
            return;

        for (final TinkerGraphStep<?, ?> tinkerGraphStep : TraversalHelper.getStepsOfClass(TinkerGraphStep.class, traversal)) {
            Step<?, ?> currentStep = tinkerGraphStep.getNextStep();
            while (currentStep instanceof AllenFilterStep || currentStep instanceof HasStep || currentStep instanceof NoOpBarrierStep) {
                if (currentStep instanceof AllenFilterStep) {
                    tinkerGraphStep.addAllenFilterStep((AllenFilterStep) currentStep);
                    TraversalHelper.copyLabels(currentStep, currentStep.getPreviousStep(), false);
                    traversal.removeStep(currentStep);
                }
                currentStep = currentStep.getNextStep();
            }
        }
    }

    public static TemporalIndexOptimizationStrategy instance() {
        return INSTANCE;
    }
}
