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
package org.apache.tinkerpop.gremlin.tinkergraph.process.traversal;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.Date;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class AllenTemporalIntegrationTest {

    private TinkerGraph graph;
    private GraphTraversalSource g;

    @Before
    public void setUp() {
        graph = TinkerGraph.open();
        g = graph.traversal();
    }

    @After
    public void tearDown() {
        graph.close();
    }

    @Test
    public void shouldNormalizeStringTemporalPropertiesForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "reference").
                property(Lifetime.START_TIME, date("2024-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2028-01-01T00:00:00Z")).
                next();

        g.addV("interval").
                property("name", "string-overlap").
                property(Lifetime.START_TIME, "2020-01-01T00:00:00Z").
                property(Lifetime.END_TIME, "2025-01-01T00:00:00Z").
                next();
        g.addV("interval").
                property("name", "string-before").
                property(Lifetime.START_TIME, "2018-01-01T00:00:00Z").
                property(Lifetime.END_TIME, "2020-01-01T00:00:00Z").
                next();

        assertThat(g.V().hasLabel("interval").temporalOverlaps(reference).values("name").toList(),
                contains("string-overlap"));
    }

    @Test
    public void shouldTreatMissingEndTimeAsOpenEndedForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "reference").
                property(Lifetime.START_TIME, date("2020-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2030-01-01T00:00:00Z")).
                next();

        g.addV("interval").
                property("name", "open-ended").
                property(Lifetime.START_TIME, "2020-01-01T00:00:00Z").
                next();
        g.addV("interval").
                property("name", "same-end").
                property(Lifetime.START_TIME, "2020-01-01T00:00:00Z").
                property(Lifetime.END_TIME, "2030-01-01T00:00:00Z").
                next();

        assertThat(g.V().hasLabel("interval").temporalStartedBy(reference).values("name").toList(),
                contains("open-ended"));
    }

    @Test
    public void shouldTreatReferenceWithoutTemporalPropertiesAsAllTimeForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "all-time-reference").
                next();

        g.addV("interval").
                property("name", "bounded-current").
                property(Lifetime.START_TIME, date("2024-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2025-01-01T00:00:00Z")).
                next();

        assertThat(g.V().hasLabel("interval").temporalDuring(reference).values("name").toList(),
                contains("bounded-current"));
    }

    @Test
    public void shouldTreatCurrentWithoutTemporalPropertiesAsAllTimeForAllenRelations() {
        final Vertex reference = g.addV("interval").
                property("name", "bounded-reference").
                property(Lifetime.START_TIME, date("2024-01-01T00:00:00Z")).
                property(Lifetime.END_TIME, date("2025-01-01T00:00:00Z")).
                next();

        g.addV("interval").
                property("name", "all-time-current").
                next();

        assertThat(g.V().hasLabel("interval").temporalContains(reference).values("name").toList(),
                contains("all-time-current"));
    }

    @Test
    public void shouldTreatElementsWithoutTemporalPropertiesAsEqualAllTimeIntervals() {
        final Vertex reference = g.addV("interval").
                property("name", "all-time-reference").
                next();

        g.addV("interval").
                property("name", "all-time-current").
                next();

        assertThat(g.V().has("name", "all-time-current").temporalEquals(reference).values("name").toList(),
                contains("all-time-current"));
    }

    private static Date date(final String instant) {
        return Date.from(Instant.parse(instant));
    }
}
