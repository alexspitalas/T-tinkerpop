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

import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.VertexProgramStep;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AtTimeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WindowStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.process.computer.Computer;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal compile-time strategy that handles the OLAP case for {@code atTime}.
 *
 * <p>Regular OLTP traversals need no strategy: {@link AtTimeStep} wraps passing elements in
 * {@link org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex} /
 * {@link org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge} decorators and the
 * temporal filter propagates automatically through graph navigation.</p>
 *
 * <p>OLAP algorithms ({@code pageRank()}, {@code shortestPath()}, etc.) operate natively on 
 * the underlying storage via a {@link org.apache.tinkerpop.gremlin.process.computer.GraphComputer}. 
 * Because GraphComputers bypass read-only wrapper graphs, this strategy configures the 
 * {@link VertexProgramStep}'s {@link Computer} with standard {@code GraphFilter}s 
 * (using {@code vertices()}, {@code edges()}, and {@code vertexProperties()}) to enforce 
 * the temporal bounds at the distributed execution layer.</p>
 *
 * <p>The strategy is a no-op when no {@link AtTimeStep}, {@link WindowStep} or no {@link VertexProgramStep}
 * is present, so it is safe to register globally.</p>
 *
 * @author Alex Spitalas
 */
public final class TemporalGraphViewStrategy
        extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy>
        implements TraversalStrategy.OptimizationStrategy {

    private static final TemporalGraphViewStrategy INSTANCE = new TemporalGraphViewStrategy();

    private TemporalGraphViewStrategy() {}

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        final boolean isComputerTraversal = traversal.getParent() instanceof VertexProgramStep;
        if (!traversal.isRoot() && !isComputerTraversal) return;

        // Fast-path: no AtTimeStep or WindowStep → nothing to do.
        final List<AtTimeStep> atTimeSteps = TraversalHelper.getStepsOfClass(AtTimeStep.class, traversal);
        final List<WindowStep> windowSteps = TraversalHelper.getStepsOfClass(WindowStep.class, traversal);
        if (atTimeSteps.isEmpty() && windowSteps.isEmpty()) return;

        // Fast-path: no OLAP step → nothing to do.
        if (!isComputerTraversal && !TraversalHelper.hasStepOfAssignableClass(VertexProgramStep.class, traversal)) return;

        rewriteVertexMoves(traversal);
        if (isComputerTraversal) return;

        // Configure the GraphComputer's GraphFilter for temporal constraints
        final boolean isWindow = !windowSteps.isEmpty();
        final Date start = isWindow ? windowSteps.get(0).getWindowStart() : atTimeSteps.get(0).getInstant();
        final Date end = isWindow ? windowSteps.get(0).getWindowEnd() : start;

        for (final VertexProgramStep step : TraversalHelper.getStepsOfAssignableClass(VertexProgramStep.class, traversal)) {
            Computer computer = step.getComputer();
            
            if (isWindow) {
                computer = computer.vertices(__.window(start, end))
                                   .edges((Traversal) __.outE().window(start, end))
                                   .vertexProperties((Traversal) __.properties().window(start, end));
            } else {
                computer = computer.vertices(__.atTime(start))
                                   .edges((Traversal) __.outE().atTime(start))
                                   .vertexProperties((Traversal) __.properties().atTime(start));
            }
            
            step.setComputer(computer);
        }
    }

    public static TemporalGraphViewStrategy instance() {
        return INSTANCE;
    }

    private void rewriteVertexMoves(final Traversal.Admin<?, ?> traversal) {
        Lifetime currentBounds = null;

        for (final Step<?, ?> step : new ArrayList<>(traversal.getSteps())) {
            if (step instanceof WindowStep) {
                final WindowStep<?> windowStep = (WindowStep<?>) step;
                currentBounds = Lifetime.from(windowStep.getWindowStart(), windowStep.getWindowEnd());
            } else if (step instanceof AtTimeStep) {
                final Date instant = ((AtTimeStep<?>) step).getInstant();
                currentBounds = Lifetime.from(instant, instant);
            } else if (currentBounds != null && step instanceof VertexStep && ((VertexStep<?>) step).returnsVertex()) {
                rewriteVertexMove(traversal, (VertexStep<Vertex>) step, currentBounds);
            }
        }
    }

    private void rewriteVertexMove(final Traversal.Admin<?, ?> traversal, final VertexStep<Vertex> step,
                                   final Lifetime bounds) {
        final WindowStep<Vertex> vertexWindowStep = new WindowStep<>(traversal, bounds);

        TraversalHelper.insertAfterStep(vertexWindowStep, step, traversal);
        TraversalHelper.copyLabels(step, vertexWindowStep, true);
    }
}
