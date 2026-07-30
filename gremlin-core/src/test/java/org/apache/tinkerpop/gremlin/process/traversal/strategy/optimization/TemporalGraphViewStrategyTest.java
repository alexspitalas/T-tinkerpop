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

import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.TraversalVertexProgramStep;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WindowStep;
import org.apache.tinkerpop.gremlin.process.traversal.util.DefaultTraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;
import org.junit.Test;

import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TemporalGraphViewStrategyTest {

    @Test
    public void shouldRewriteAtTimeVertexMoveWithPointLifetime() {
        final Date instant = DatetimeHelper.parse("2024-01-01T09:00:00Z");
        final Traversal.Admin<?, ?> traversal = applyStrategyToComputerChild(__.V().atTime(instant).out().asAdmin());

        final List<WindowStep> windowSteps = TraversalHelper.getStepsOfClass(WindowStep.class, traversal);
        assertEquals(1, windowSteps.size());
        assertEquals(instant, windowSteps.get(0).getWindowStart());
        assertEquals(instant, windowSteps.get(0).getWindowEnd());
    }

    @Test
    public void shouldRewriteWindowVertexMoveWithSameLifetime() {
        final Date start = DatetimeHelper.parse("2024-01-01T09:00:00Z");
        final Date end = DatetimeHelper.parse("2024-01-01T10:00:00Z");
        final Traversal.Admin<?, ?> traversal = applyStrategyToComputerChild(__.V().window(start, end).out().asAdmin());

        final List<WindowStep> windowSteps = TraversalHelper.getStepsOfClass(WindowStep.class, traversal);
        assertEquals(2, windowSteps.size());
        assertEquals(start, windowSteps.get(1).getWindowStart());
        assertEquals(end, windowSteps.get(1).getWindowEnd());
    }

    private static Traversal.Admin<?, ?> applyStrategyToComputerChild(final Traversal.Admin<?, ?> traversal) {
        final Traversal.Admin<?, ?> rootTraversal = new DefaultGraphTraversal<>();
        final TraversalVertexProgramStep parent = new TraversalVertexProgramStep(rootTraversal, traversal);
        rootTraversal.addStep(parent);
        traversal.setParent(parent);

        final TraversalStrategies strategies = new DefaultTraversalStrategies();
        strategies.addStrategies(TemporalGraphViewStrategy.instance());
        traversal.setStrategies(strategies);
        traversal.applyStrategies();
        return traversal;
    }
}
