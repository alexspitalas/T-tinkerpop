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
package org.apache.tinkerpop.gremlin.server;

import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.driver.remote.DriverRemoteConnection;
import org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.TemporalP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.util.ser.Serializers;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end integration tests verifying T-Gremlin temporal bytecode execution over a live
 * {@link GremlinServer} using both GraphBinary v1 and GraphSON v3 serializers.
 */
public class TemporalServerIntegrateTest extends AbstractGremlinServerIntegrationTest {

    @Test
    public void shouldExecuteTemporalBytecodeOverGraphBinaryV1() throws Exception {
        runTemporalScenario(Serializers.GRAPHBINARY_V1);
    }

    @Test
    public void shouldExecuteTemporalBytecodeOverGraphSONV2() throws Exception {
        runTemporalScenario(Serializers.GRAPHSON_V2);
    }

    @Test
    public void shouldExecuteTemporalBytecodeOverGraphSONV3() throws Exception {
        runTemporalScenario(Serializers.GRAPHSON_V3);
    }

    private void runTemporalScenario(final Serializers serializer) throws Exception {
        final Cluster cluster = TestClientFactory.build().serializer(serializer).create();
        try {
            final GraphTraversalSource g = traversal().withRemote(DriverRemoteConnection.using(cluster, "g"));

            g.V().drop().iterate();

            // Create temporal vertices and edge with multi-interval lifetimes
            g.addV("person").property("name", "alice").lifetime(1000L, 2000L).addInterval(3000L, 4000L).iterate();
            g.addV("person").property("name", "bob").lifetime(1500L, 3500L).iterate();
            g.V().has("name", "alice")
                    .addE("knows").to(__.V().has("name", "bob"))
                    .lifetime(1500L, 2000L)
                    .iterate();

            // Snapshot and window views
            final List<Object> at1750 = g.V().atTime(1750L).values("name").toList();
            assertEquals(2, at1750.size());
            assertTrue(at1750.contains("alice") && at1750.contains("bob"));

            final List<Object> winEarly = g.V().window(Lifetime.from(1000L, 1200L)).values("name").toList();
            assertEquals(1, winEarly.size());
            assertEquals("alice", winEarly.get(0));

            // TemporalP with ANY quantifier, negation, and Ligozat convex interval (between)
            // alice=[1000,2000) U [3000,4000), bob=[1500,3500)
            // Against [1200,2500): alice's [1000,2000) Allen-overlaps [1200,2500), bob is overlappedBy.
            final List<Object> anyOverlaps = g.V()
                    .where(__.getLifetime().is(TemporalP.overlaps(Lifetime.from(1200L, 2500L)).any()))
                    .values("name").toList();
            assertEquals(1, anyOverlaps.size());
            assertEquals("alice", anyOverlaps.get(0));

            final List<Object> notAnyOverlaps = g.V()
                    .where(__.getLifetime().is(TemporalP.overlaps(Lifetime.from(1200L, 2500L)).any().negate()))
                    .values("name").toList();
            assertEquals(1, notAnyOverlaps.size());
            assertEquals("bob", notAnyOverlaps.get(0));

            final List<Object> notOverlaps = g.V()
                    .where(__.getLifetime().is(TemporalP.overlaps(Lifetime.from(1200L, 2500L)).negate()))
                    .values("name").toList();
            assertEquals(2, notOverlaps.size());

            final List<Object> betweenMatches = g.V()
                    .where(__.getLifetime().is(
                            TemporalP.between(Temporal.precedes, Temporal.overlaps, Lifetime.from(1500L, 3500L)).any()))
                    .values("name").toList();
            assertEquals(1, betweenMatches.size());
            assertEquals("alice", betweenMatches.get(0));

            final List<Object> directWhereOverlaps = g.V()
                    .where(TemporalP.overlaps(Lifetime.from(1200L, 2500L)).any())
                    .values("name").toList();
            assertEquals(1, directWhereOverlaps.size());
            assertEquals("alice", directWhereOverlaps.get(0));

            // dropInterval with DropMode.HARD and returning Lifetime over the wire
            final Lifetime updatedAlice = g.V().has("name", "alice")
                    .dropInterval(3000L, 4000L, Lifetime.DropMode.HARD)
                    .getLifetime().next();
            assertEquals(Lifetime.from(1000L, 2000L), updatedAlice);

            // TBFS and TemporalPageRank over remote connection
            final Map<Vertex, Long> arrivals = g.V().has("name", "alice")
                    .tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, 0L).next();
            assertEquals(1, arrivals.size());
            assertEquals(Long.valueOf(2000L), arrivals.values().iterator().next());

            final List<Object> ranks = g.V().temporalPageRank()
                    .with(TemporalPageRank.alpha, 0.85)
                    .with(TemporalPageRank.propertyName, "tpr")
                    .values("tpr").toList();
            assertEquals(2, ranks.size());
            assertTrue(ranks.stream().anyMatch(r -> ((Number) r).doubleValue() > 0.0d));
        } finally {
            cluster.close();
        }
    }
}
