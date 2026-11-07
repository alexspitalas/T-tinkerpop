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

import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.TemporalShortestPathVertexProgramStep;
import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.TraversalVertexProgramStep;
import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.VertexProgramStep;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AtTimeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WindowStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeOtherVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.EdgeVertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.VertexStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.process.computer.Computer;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.Collections;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A minimal compile-time strategy that handles the OLAP case for {@code atTime} and {@code window}.
 *
 * <p>Regular OLTP traversals need no strategy: {@link AtTimeStep} and {@link WindowStep} wrap passing elements in
 * {@link org.apache.tinkerpop.gremlin.structure.temporal.TemporalVertex} /
 * {@link org.apache.tinkerpop.gremlin.structure.temporal.TemporalEdge} decorators and the
 * temporal filter propagates automatically through graph navigation.</p>
 *
 * <p>OLAP algorithms ({@code pageRank()}, {@code shortestPath()}, etc.) operate natively on 
 * the underlying storage via a {@link org.apache.tinkerpop.gremlin.process.computer.GraphComputer}. 
 * Because GraphComputers bypass read-only wrapper graphs, this strategy configures the 
 * {@link VertexProgramStep}'s {@link Computer} with standard {@code GraphFilter}s 
 * (using {@code vertices()} and {@code edges()}) to enforce 
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
    private static final Set<Class<? extends OptimizationStrategy>> PRIORS =
            Collections.singleton(IncidentToAdjacentStrategy.class);

    private TemporalGraphViewStrategy() {}

    @Override
    public Set<Class<? extends OptimizationStrategy>> applyPrior() {
        return PRIORS;
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        final boolean isComputerTraversal = traversal.getParent() instanceof VertexProgramStep;
        if (!traversal.isRoot() && !isComputerTraversal) return;

        final List<AtTimeStep> atTimeSteps = TraversalHelper.getStepsOfClass(AtTimeStep.class, traversal);
        final List<WindowStep> windowSteps = TraversalHelper.getStepsOfClass(WindowStep.class, traversal);

        if (isComputerTraversal) {
            if (!atTimeSteps.isEmpty() || !windowSteps.isEmpty()) {
                rewriteVertexMoves(traversal);
            }
            return;
        }

        // Fast-path: no OLAP step → nothing to do.
        if (!TraversalHelper.hasStepOfAssignableClass(VertexProgramStep.class, traversal)) return;

        if (!atTimeSteps.isEmpty() || !windowSteps.isEmpty()) {
            rewriteVertexMoves(traversal);
        }

        // Track active temporal bounds in traversal order across top-level steps and TraversalVertexProgramStep children,
        // and configure subsequent non-traversal VertexProgramSteps (e.g. PageRank, ShortestPath, TemporalShortestPath).
        Lifetime activeWindow = null;
        int temporalStepCount = 0;
        Date singleAtTimeInstant = null;

        for (final Step<?, ?> step : traversal.getSteps()) {
            if (step instanceof WindowStep) {
                final Lifetime w = ((WindowStep<?>) step).getWindow();
                activeWindow = activeWindow == null ? w : activeWindow.intersection(w);
                temporalStepCount++;
                singleAtTimeInstant = null;
            } else if (step instanceof AtTimeStep) {
                final Date inst = ((AtTimeStep<?>) step).getInstant();
                final Lifetime pt = Lifetime.from(inst, inst);
                activeWindow = activeWindow == null ? pt : activeWindow.intersection(pt);
                temporalStepCount++;
                singleAtTimeInstant = (temporalStepCount == 1) ? inst : null;
            } else if (step instanceof TraversalVertexProgramStep) {
                for (final Traversal.Admin<?, ?> child : ((TraversalVertexProgramStep) step).getGlobalChildren()) {
                    for (final Step<?, ?> childStep : child.getSteps()) {
                        if (childStep instanceof WindowStep) {
                            final Lifetime w = ((WindowStep<?>) childStep).getWindow();
                            activeWindow = activeWindow == null ? w : activeWindow.intersection(w);
                            temporalStepCount++;
                            singleAtTimeInstant = null;
                        } else if (childStep instanceof AtTimeStep) {
                            final Date inst = ((AtTimeStep<?>) childStep).getInstant();
                            final Lifetime pt = Lifetime.from(inst, inst);
                            activeWindow = activeWindow == null ? pt : activeWindow.intersection(pt);
                            temporalStepCount++;
                            singleAtTimeInstant = (temporalStepCount == 1) ? inst : null;
                        }
                    }
                }
            } else if (step instanceof VertexProgramStep && activeWindow != null) {
                final VertexProgramStep vpStep = (VertexProgramStep) step;
                Computer computer = vpStep.getComputer();

                if (singleAtTimeInstant != null) {
                    computer = computer.vertices(__.atTime(singleAtTimeInstant))
                                       .edges((Traversal) __.bothE().atTime(singleAtTimeInstant));
                } else {
                    computer = computer.vertices(__.window(activeWindow))
                                       .edges((Traversal) __.bothE().window(activeWindow));
                }

                vpStep.setComputer(computer);
                if (vpStep instanceof TemporalShortestPathVertexProgramStep) {
                    final TemporalShortestPathVertexProgramStep tspStep = (TemporalShortestPathVertexProgramStep) vpStep;
                    final Traversal.Admin<Vertex, ?> edgeTrav = tspStep.getEdgeTraversal();
                    edgeTrav.addStep(new WindowStep<>(edgeTrav, activeWindow));
                    tspStep.setEdgeTraversal(edgeTrav);
                }
            }
        }
    }

    public static TemporalGraphViewStrategy instance() {
        return INSTANCE;
    }

    private void rewriteVertexMoves(final Traversal.Admin<?, ?> traversal) {
        Lifetime currentBounds = null;

        for (final Step<?, ?> step : new ArrayList<>(traversal.getSteps())) {
            if (step instanceof WindowStep) {
                final Lifetime stepBounds = ((WindowStep<?>) step).getWindow();
                currentBounds = currentBounds == null ? stepBounds : currentBounds.intersection(stepBounds);
            } else if (step instanceof AtTimeStep) {
                final Date instant = ((AtTimeStep<?>) step).getInstant();
                final Lifetime stepBounds = Lifetime.from(instant, instant);
                currentBounds = currentBounds == null ? stepBounds : currentBounds.intersection(stepBounds);
            } else if (currentBounds != null && ((step instanceof VertexStep && ((VertexStep<?>) step).returnsVertex())
                    || step instanceof EdgeVertexStep || step instanceof EdgeOtherVertexStep)) {
                rewriteVertexMove(traversal, (Step<?, Vertex>) step, currentBounds);
            }
        }
    }

    private void rewriteVertexMove(final Traversal.Admin<?, ?> traversal, final Step<?, Vertex> step,
                                   final Lifetime bounds) {
        final WindowStep<Vertex> vertexWindowStep = new WindowStep<>(traversal, bounds);

        TraversalHelper.insertAfterStep(vertexWindowStep, step, traversal);
        TraversalHelper.copyLabels(step, vertexWindowStep, true);
    }
}
