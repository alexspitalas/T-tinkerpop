/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.AbstractGremlinProcessTest;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.junit.Assert.*;

@RunWith(GremlinProcessRunner.class)
public abstract class AllenTemporalTest extends AbstractGremlinProcessTest {

    public abstract Traversal<Vertex, Vertex> get_g_V_temporalBeforeX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalAfterX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalMeetsX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalMetByX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalOverlapsX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalOverlappedByX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalStartsX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalStartedByX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalFinishesX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalFinishedByX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalDuringX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalContainsX1X(final Object v1Id);
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalEqualsX1X(final Object v1Id);

    @Test
    @LoadGraphWith(MODERN)
    public void g_V_temporalBeforeX1X() {
        final Object v1 = convertToVertexId("lop"); // lop: [2011-04-01T00:00, 2011-04-01T00:00]
        final Traversal<Vertex, Vertex> t = get_g_V_temporalBeforeX1X(v1);
        printTraversalForm(t);
        assertFalse(t.hasNext());
    }

    @Test
    @LoadGraphWith(MODERN)
    public void g_V_temporalAfterX1X() {
        final Object v1 = convertToVertexId("lop");
        final Traversal<Vertex, Vertex> t = get_g_V_temporalAfterX1X(v1);
        printTraversalForm(t);
        assertFalse(t.hasNext());
    }

    @Test
    @LoadGraphWith(MODERN)
    public void g_V_temporalEqualsX1X() {
        final Object v1 = convertToVertexId("lop");
        final Traversal<Vertex, Vertex> t = get_g_V_temporalEqualsX1X(v1);
        printTraversalForm(t);
        int count = 0; Set<Vertex> vs = new HashSet<>();
        while (t.hasNext()) { vs.add(t.next()); count++; }
        assertEquals(1, count);
        assertTrue(vs.iterator().next().id().equals(v1));
    }

    // Additional tests for other relations can follow similar patterns...
    // e.g. temporalMeets, temporalOverlaps, temporalContains, etc.

    public static class Traversals extends AllenTemporalTest {
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalBeforeX1X(final Object v1Id) {
            // Look up the actual Vertex by its id
            Vertex ref = g.V(v1Id).next();
            // Then call the strict Element-typed step
            return g.V().temporalBefore(ref);
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalAfterX1X(final Object v1Id) {
            return g.V().temporalAfter((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalEqualsX1X(final Object v1Id) {
            return g.V().temporalEquals((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalMeetsX1X(final Object v1Id) {
            return g.V().temporalMeets((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalMetByX1X(final Object v1Id) {
            return g.V().temporalMetBy((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalOverlapsX1X(final Object v1Id) {
            return g.V().temporalOverlaps((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalOverlappedByX1X(final Object v1Id) {
            return g.V().temporalOverlappedBy((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalStartsX1X(final Object v1Id) {
            return g.V().temporalStarts((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalStartedByX1X(final Object v1Id) {
            return g.V().temporalStartedBy((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalFinishesX1X(final Object v1Id) {
            return g.V().temporalFinishes((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalFinishedByX1X(final Object v1Id) {
            return g.V().temporalFinishedBy((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalDuringX1X(final Object v1Id) {
            return g.V().temporalDuring((Vertex) g.V(v1Id).next());
        }
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalContainsX1X(final Object v1Id) {
            return g.V().temporalContains((Vertex) g.V(v1Id).next());
        }
    }
}
