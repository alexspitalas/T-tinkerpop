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

import org.apache.tinkerpop.gremlin.process.computer.ComputerResult;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.TemporalPageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.traversal.TemporalP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoMapper;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoReader;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoVersion;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoWriter;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.TransactionException;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceFactory;
import org.apache.tinkerpop.gremlin.tinkergraph.process.traversal.step.sideEffect.TinkerGraphStep;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.AbstractTinkerGraph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerTransactionGraph;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end tests covering:
 * <ol>
 *   <li>Multi-element ACID isolation &amp; temporal write-skew prevention on {@link TinkerTransactionGraph}</li>
 *   <li>Transactional {@code IntervalTree} indexing on {@link TinkerTransactionGraph}</li>
 *   <li>Distributed OLAP {@link TemporalPageRankVertexProgram} parity with OLTP {@code temporalPageRank()}</li>
 * </ol>
 */
public class TemporalProductionHardeningTest {

    // ════════════════════════════════════════════════════════════════════════
    // A. ACID & CONCURRENCY ISOLATION FOR LIFETIME CASCADES
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void shouldLeaveGraphCleanWhenSoftDropCascadeFailsOnTransactionalGraph() {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1 = g.addV("person").property("name", "v1").lifetime(1000L, 5000L)
                .lifetimeProperty("role", "eng", 1000L, 4000L).next();
        final Vertex v2 = g.addV("person").property("name", "v2").lifetime(1000L, 5000L).next();
        final Vertex v3 = g.addV("person").property("name", "v3").lifetime(1000L, 5000L).next();
        final Edge eOk = g.addE("knows").from(v1).to(v2).lifetime(1000L, 4000L).next();
        final Edge eFail = g.addE("knows").from(v1).to(v3).lifetime(3000L, 5000L).next();
        graph.tx().commit();

        try {
            // Soft drop [3000, 5000) would empty eFail -> must fail with ZERO mutations to v1, role, or eOk
            g.V(v1.id()).dropInterval(3000L, 5000L, Lifetime.DropMode.SOFT).iterate();
            fail("Expected IllegalArgumentException for soft drop emptying incident edge");
        } catch (final IllegalArgumentException expected) {
            graph.tx().rollback();
        }

        assertEquals(Lifetime.from(1000L, 5000L), Lifetime.fromProperties(g.V(v1.id()).next()));
        assertEquals(Lifetime.from(1000L, 4000L), Lifetime.fromProperties(g.E(eOk.id()).next()));
        assertEquals(Lifetime.from(3000L, 5000L), Lifetime.fromProperties(g.E(eFail.id()).next()));
        assertEquals(Lifetime.from(1000L, 4000L), Lifetime.fromProperties(g.V(v1.id()).next().property("role")));
        graph.tx().rollback();
    }

    @Test
    public void shouldPreventWriteSkewBetweenConcurrentVertexNarrowingAndEdgeWidening() throws Exception {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1 = g.addV("person").property("name", "v1").lifetime(0L, 1000L).next();
        final Vertex v2 = g.addV("person").property("name", "v2").lifetime(0L, 1000L).next();
        final Edge e = g.addE("knows").from(v1).to(v2).lifetime(200L, 400L).next();
        graph.tx().commit();

        final CountDownLatch bothMutated = new CountDownLatch(2);
        final CountDownLatch tx1Committed = new CountDownLatch(1);
        final AtomicReference<Throwable> tx2Error = new AtomicReference<>();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> f1 = exec.submit(() -> {
                try {
                    final GraphTraversalSource g1 = graph.traversal();
                    // Narrow v1 to [0, 500); existing e [200, 400) fits so tx1 does NOT modify e
                    try {
                        g1.V(v1.id()).lifetime(0L, 500L).iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    try {
                        assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    graph.tx().commit();
                } finally {
                    tx1Committed.countDown();
                }
            });

            final Future<?> f2 = exec.submit(() -> {
                try {
                    final GraphTraversalSource g2 = graph.traversal();
                    // Concurrently widen e to [200, 800) against v1's pre-commit snapshot [0, 1000)
                    try {
                        g2.E(e.id()).lifetime(200L, 800L).iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    assertTrue(tx1Committed.await(5, TimeUnit.SECONDS));
                    graph.tx().commit();
                } catch (final Throwable t) {
                    tx2Error.set(t);
                    if (graph.tx().isOpen()) {
                        graph.tx().rollback();
                    }
                }
            });

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertTrue("Expected TransactionException preventing write-skew, got: " + tx2Error.get(),
                tx2Error.get() instanceof TransactionException);

        // Final committed state must strictly preserve referential integrity (e subset of v1)
        final Lifetime committedV1 = Lifetime.fromProperties(g.V(v1.id()).next());
        final Lifetime committedE = Lifetime.fromProperties(g.E(e.id()).next());
        assertEquals(Lifetime.from(0L, 500L), committedV1);
        assertEquals(Lifetime.from(200L, 400L), committedE);
        assertTrue(committedV1.contains(committedE));
        graph.tx().rollback();
    }

    @Test
    public void shouldPreventWriteSkewInReversedCommitOrderEdgeWidensFirst() throws Exception {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1 = g.addV("person").property("name", "v1").lifetime(0L, 1000L).next();
        final Vertex v2 = g.addV("person").property("name", "v2").lifetime(0L, 1000L).next();
        final Edge e = g.addE("knows").from(v1).to(v2).lifetime(200L, 400L).next();
        graph.tx().commit();

        final CountDownLatch bothMutated = new CountDownLatch(2);
        final CountDownLatch txEdgeCommitted = new CountDownLatch(1);
        final AtomicReference<Throwable> txVertexError = new AtomicReference<>();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> fEdge = exec.submit(() -> {
                try {
                    try {
                        graph.traversal().E(e.id()).lifetime(200L, 800L).iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    try {
                        assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    graph.tx().commit();
                } finally {
                    txEdgeCommitted.countDown();
                }
            });

            final Future<?> fVertex = exec.submit(() -> {
                try {
                    try {
                        graph.traversal().V(v1.id()).lifetime(0L, 500L).iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    assertTrue(txEdgeCommitted.await(5, TimeUnit.SECONDS));
                    graph.tx().commit();
                } catch (final Throwable t) {
                    txVertexError.set(t);
                    if (graph.tx().isOpen()) {
                        graph.tx().rollback();
                    }
                }
            });

            fEdge.get(10, TimeUnit.SECONDS);
            fVertex.get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertTrue("Expected TransactionException when vertex narrowing commits after concurrent edge widening, got: " + txVertexError.get(),
                txVertexError.get() instanceof TransactionException);

        final Lifetime committedV1 = Lifetime.fromProperties(g.V(v1.id()).next());
        final Lifetime committedE = Lifetime.fromProperties(g.E(e.id()).next());
        assertEquals(Lifetime.from(0L, 1000L), committedV1);
        assertEquals(Lifetime.from(200L, 800L), committedE);
        assertTrue(committedV1.contains(committedE));
        graph.tx().rollback();
    }

    @Test
    public void shouldPreventWriteSkewOnUntimedEdgeBetweenConcurrentlyNarrowedEndpoints() throws Exception {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1 = g.addV("person").property("name", "v1").lifetime(0L, 1000L).next();
        final Vertex v2 = g.addV("person").property("name", "v2").lifetime(0L, 1000L).next();
        g.addE("knows").from(v1).to(v2).next(); // untimed edge inherits v1 ∩ v2
        graph.tx().commit();

        final CountDownLatch bothMutated = new CountDownLatch(2);
        final CountDownLatch tx1Committed = new CountDownLatch(1);
        final AtomicReference<Throwable> tx2Error = new AtomicReference<>();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> f1 = exec.submit(() -> {
                try {
                    final GraphTraversalSource g1 = graph.traversal();
                    try {
                        g1.V(v1.id()).lifetime(0L, 400L).iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    try {
                        assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    graph.tx().commit();
                } finally {
                    tx1Committed.countDown();
                }
            });

            final Future<?> f2 = exec.submit(() -> {
                try {
                    final GraphTraversalSource g2 = graph.traversal();
                    try {
                        g2.V(v2.id()).lifetime(600L, 1000L).iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    assertTrue(tx1Committed.await(5, TimeUnit.SECONDS));
                    graph.tx().commit();
                } catch (final Throwable t) {
                    tx2Error.set(t);
                    if (graph.tx().isOpen()) {
                        graph.tx().rollback();
                    }
                }
            });

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertTrue("Expected TransactionException preventing disjoint endpoints on untimed edge, got: " + tx2Error.get(),
                tx2Error.get() instanceof TransactionException);

        final Lifetime committedV1 = Lifetime.fromProperties(g.V(v1.id()).next());
        final Lifetime committedV2 = Lifetime.fromProperties(g.V(v2.id()).next());
        assertFalse("Untimed edge endpoints must still overlap", committedV1.intersection(committedV2).isEmpty());
        graph.tx().rollback();
    }

    @Test
    public void shouldAllowNonConflictingConcurrentNonTemporalPropertyUpdate() throws Exception {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1 = g.addV("person").property("name", "v1").lifetime(0L, 1000L).next();
        final Vertex v2 = g.addV("person").property("name", "v2").lifetime(0L, 1000L).next();
        g.addE("knows").from(v1).to(v2).lifetime(200L, 400L).next();
        graph.tx().commit();

        final CountDownLatch bothMutated = new CountDownLatch(2);
        final CountDownLatch tx1Committed = new CountDownLatch(1);
        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> f1 = exec.submit(() -> {
                try {
                    try {
                        graph.traversal().V(v2.id()).property("name", "v2-updated").iterate();
                    } finally {
                        bothMutated.countDown();
                    }
                    try {
                        assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    graph.tx().commit();
                } finally {
                    tx1Committed.countDown();
                }
            });

            final Future<?> f2 = exec.submit(() -> {
                try {
                    graph.traversal().V(v1.id()).lifetime(0L, 800L).iterate();
                } finally {
                    bothMutated.countDown();
                }
                try {
                    assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    assertTrue(tx1Committed.await(5, TimeUnit.SECONDS));
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                graph.tx().commit();
            });

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertEquals(Lifetime.from(0L, 800L), Lifetime.fromProperties(g.V(v1.id()).next()));
        assertEquals("v2-updated", g.V(v2.id()).values("name").next());
        graph.tx().rollback();
    }

    @Test
    public void shouldRejectConcurrentStructureApiLifetimeWriteSkewWithoutPriorRead() throws Exception {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1Init = g.addV("person").property("name", "v1").lifetime(1000L, 5000L).next();
        final Vertex v2Init = g.addV("person").property("name", "v2").lifetime(1000L, 5000L).next();
        final Edge eInit = g.addE("knows").from(v1Init).to(v2Init).lifetime(1000L, 2000L).next();
        final Object v1Id = v1Init.id();
        final Object eId = eInit.id();
        graph.tx().commit();

        final CountDownLatch bothMutated = new CountDownLatch(2);
        final CountDownLatch tx1Committed = new CountDownLatch(1);
        final AtomicReference<Throwable> tx2Error = new AtomicReference<>();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<?> f1 = exec.submit(() -> {
                try {
                    try {
                        final Vertex v1 = graph.vertices(v1Id).next();
                        v1.property(Lifetime.TEMPORAL_INTERVALS, "1000:2000");
                    } finally {
                        bothMutated.countDown();
                    }
                    try {
                        assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    graph.tx().commit();
                } finally {
                    tx1Committed.countDown();
                }
            });

            final Future<?> f2 = exec.submit(() -> {
                try {
                    try {
                        final Edge e = graph.edges(eId).next();
                        // Widen e via Structure API without reading v1 in tx2
                        e.property(Lifetime.TEMPORAL_INTERVALS, "1000:4000");
                    } finally {
                        bothMutated.countDown();
                    }
                    assertTrue(bothMutated.await(5, TimeUnit.SECONDS));
                    assertTrue(tx1Committed.await(5, TimeUnit.SECONDS));
                    graph.tx().commit();
                } catch (final Throwable t) {
                    tx2Error.set(t);
                    if (graph.tx().isOpen()) {
                        graph.tx().rollback();
                    }
                }
            });

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }

        assertTrue("Expected TransactionException preventing Structure API write-skew without prior read, got: " + tx2Error.get(),
                tx2Error.get() instanceof TransactionException);

        final Lifetime committedV1 = Lifetime.fromProperties(g.V(v1Id).next());
        final Lifetime committedE = Lifetime.fromProperties(g.E(eId).next());
        assertEquals(Lifetime.from(1000L, 2000L), committedV1);
        assertEquals(Lifetime.from(1000L, 2000L), committedE);
        assertTrue(committedV1.contains(committedE));
        graph.tx().rollback();
    }

    // ════════════════════════════════════════════════════════════════════════
    // B. TRANSACTIONAL TEMPORAL INDEXING ON TINKERTRANSACTIONGRAPH
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void shouldUseTemporalIndexWithFullParityOnTinkerTransactionGraph() {
        final TinkerTransactionGraph indexed = TinkerTransactionGraph.open();
        final TinkerTransactionGraph unindexed = TinkerTransactionGraph.open();
        indexed.createIndex(Lifetime.TEMPORAL_INTERVALS, Vertex.class);
        indexed.createIndex(Lifetime.TEMPORAL_INTERVALS, Edge.class);

        for (final TinkerTransactionGraph graph : new TinkerTransactionGraph[]{indexed, unindexed}) {
            final GraphTraversalSource g = graph.traversal();
            final Vertex a = g.addV("person").property("name", "a").lifetime(1000L, 2000L).next();
            final Vertex b = g.addV("person").property("name", "b").lifetime(2000L, 3000L).next();
            final Vertex c = g.addV("person").property("name", "c").lifetime(2500L, 4000L).addInterval(6000L, 7000L).next();
            final Vertex d = g.addV("person").property("name", "d").lifetime(5000L, 6000L).next();
            final Vertex s = g.addV("person").property("name", "static").next(); // open-world UNBOUNDED
            g.addE("link").from(a).to(s).property("name", "e1").lifetime(1200L, 1800L).next();
            g.addE("link").from(b).to(s).property("name", "e2").lifetime(2000L, 2500L).next();
            g.addE("link").from(s).to(s).property("name", "eStatic").next();
            graph.tx().commit();
        }

        final GraphTraversalSource gi = indexed.traversal();
        final GraphTraversalSource gu = unindexed.traversal();

        // Verify TinkerGraphStep actually binds to the temporal index on TinkerTransactionGraph
        final Lifetime q = Lifetime.from(2000L, 3000L);
        final GraphTraversal<Vertex, Vertex> probe = gi.V().temporalOverlaps(q);
        probe.asAdmin().applyStrategies();
        final TinkerGraphStep<?, ?> step = TraversalHelper.getStepsOfClass(TinkerGraphStep.class, probe.asAdmin()).get(0);
        assertNotNull("TinkerTransactionGraph should use IntervalTree index for temporalOverlaps",
                step.getIndexAllenStep(Vertex.class));

        assertEquals(new HashSet<>(gu.V().temporalIntersects(q).values("name").toList()),
                new HashSet<>(gi.V().temporalIntersects(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalOverlaps(q).values("name").toList()),
                new HashSet<>(gi.V().temporalOverlaps(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalBefore(q).values("name").toList()),
                new HashSet<>(gi.V().temporalBefore(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalMeets(q).values("name").toList()),
                new HashSet<>(gi.V().temporalMeets(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalAfter(q).values("name").toList()),
                new HashSet<>(gi.V().temporalAfter(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalMetBy(q).values("name").toList()),
                new HashSet<>(gi.V().temporalMetBy(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalContains(q).values("name").toList()),
                new HashSet<>(gi.V().temporalContains(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().temporalDisjoint(q).values("name").toList()),
                new HashSet<>(gi.V().temporalDisjoint(q).values("name").toList()));
        assertEquals(new HashSet<>(gu.V().has(Lifetime.TEMPORAL_INTERVALS, TemporalP.overlaps(q).negate()).values("name").toList()),
                new HashSet<>(gi.V().has(Lifetime.TEMPORAL_INTERVALS, TemporalP.overlaps(q).negate()).values("name").toList()));
        final Lifetime eq = Lifetime.from(1500L, 2200L);
        assertEquals(new HashSet<>(gu.E().temporalIntersects(eq).values("name").toList()),
                new HashSet<>(gi.E().temporalIntersects(eq).values("name").toList()));

        indexed.tx().rollback();
        unindexed.tx().rollback();
    }

    @Test
    public void shouldIsolateUncommittedWritesAndRollbackOnTransactionalTemporalIndex() throws Exception {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        graph.createIndex(Lifetime.TEMPORAL_INTERVALS, Vertex.class);
        final GraphTraversalSource g = graph.traversal();
        final Lifetime early = Lifetime.from(1000L, 2000L);
        final Lifetime late = Lifetime.from(5000L, 6000L);

        final Vertex existing = g.addV("person").property("name", "existing").lifetime(1000L, 2000L).next();
        final Vertex steady = g.addV("person").property("name", "steady").lifetime(1200L, 1800L).next();
        graph.tx().commit();

        // Start uncommitted modifications in current thread:
        // 1) Add new vertex in [1000, 2000)
        // 2) Move existing to [5000, 6000)
        // 3) Modify ONLY a non-temporal property on steady (must remain visible to temporal index query!)
        g.addV("person").property("name", "draft").lifetime(1100L, 1900L).next();
        g.V(existing.id()).lifetime(5000L, 6000L).iterate();
        g.V(steady.id()).property("name", "steady-renamed").iterate();

        // Current thread sees draft + steady-renamed in [1000, 2000), and existing moved to [5000, 6000)
        final Set<Object> txViewEarly = new HashSet<>(g.V().temporalIntersects(early).values("name").toList());
        assertEquals(new HashSet<>(java.util.Arrays.asList("draft", "steady-renamed")), txViewEarly);
        final Set<Object> txViewLate = new HashSet<>(g.V().temporalIntersects(late).values("name").toList());
        assertEquals(new HashSet<>(java.util.Collections.singletonList("existing")), txViewLate);

        // Concurrent reader thread MUST see only committed state!
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            final Set<Object> otherEarly = exec.submit(() -> {
                try {
                    return new HashSet<>(graph.traversal().V().temporalIntersects(early).values("name").toList());
                } finally {
                    graph.tx().rollback();
                }
            }).get(5, TimeUnit.SECONDS);
            final Set<Object> otherLate = exec.submit(() -> {
                try {
                    return new HashSet<>(graph.traversal().V().temporalIntersects(late).values("name").toList());
                } finally {
                    graph.tx().rollback();
                }
            }).get(5, TimeUnit.SECONDS);

            assertEquals(new HashSet<>(java.util.Arrays.asList("existing", "steady")), otherEarly);
            assertTrue(otherLate.isEmpty());

            // Rollback current thread -> restores original indexed view
            graph.tx().rollback();
            assertEquals(new HashSet<>(java.util.Arrays.asList("existing", "steady")),
                    new HashSet<>(g.V().temporalIntersects(early).values("name").toList()));
            assertTrue(g.V().temporalIntersects(late).toList().isEmpty());
            graph.tx().rollback();

            // Commit a new vertex & verify other thread sees it via temporal index
            g.addV("person").property("name", "committed-late").lifetime(5000L, 6000L).next();
            graph.tx().commit();
            final Set<Object> afterCommit = exec.submit(() -> {
                try {
                    return new HashSet<>(graph.traversal().V().temporalIntersects(late).values("name").toList());
                } finally {
                    graph.tx().rollback();
                }
            }).get(5, TimeUnit.SECONDS);
            assertEquals(new HashSet<>(java.util.Collections.singletonList("committed-late")), afterCommit);
        } finally {
            exec.shutdownNow();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // C. DISTRIBUTED OLAP TEMPORAL PAGERANK VERTEX PROGRAM
    // ════════════════════════════════════════════════════════════════════════

    @Test
    public void shouldMatchOltpAndOlapTemporalPageRankExactly() {
        final TinkerGraph oltpGraph = buildTemporalPageRankGraph();
        final TinkerGraph olapGraph = buildTemporalPageRankGraph();

        // 1. Default normalized Temporal PageRank
        final Map<String, Double> oltpNorm = collectRanks(oltpGraph.traversal().V()
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "tpr"), "tpr");
        final Map<String, Double> olapNorm = collectRanks(olapGraph.traversal().withComputer().V()
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "tpr"), "tpr");

        assertEquals(oltpNorm.keySet(), olapNorm.keySet());
        for (final String name : oltpNorm.keySet()) {
            assertEquals("Normalized Temporal PageRank mismatch for " + name,
                    oltpNorm.get(name), olapNorm.get(name), 1e-12);
        }

        // 2. Unnormalized with custom alpha and beta=1.0
        final TinkerGraph oltpGraph2 = buildTemporalPageRankGraph();
        final TinkerGraph olapGraph2 = buildTemporalPageRankGraph();
        final Map<String, Double> oltpRaw = collectRanks(oltpGraph2.traversal().V()
                .temporalPageRank()
                .with(TemporalPageRank.alpha, 0.7d)
                .with(TemporalPageRank.beta, 1.0d)
                .with(TemporalPageRank.normalize, false)
                .with(TemporalPageRank.propertyName, "rawTpr"), "rawTpr");
        final Map<String, Double> olapRaw = collectRanks(olapGraph2.traversal().withComputer().V()
                .temporalPageRank()
                .with(TemporalPageRank.alpha, 0.7d)
                .with(TemporalPageRank.beta, 1.0d)
                .with(TemporalPageRank.normalize, false)
                .with(TemporalPageRank.propertyName, "rawTpr"), "rawTpr");

        assertEquals(oltpRaw.keySet(), olapRaw.keySet());
        for (final String name : oltpRaw.keySet()) {
            assertEquals("Unnormalized Temporal PageRank mismatch for " + name,
                    oltpRaw.get(name), olapRaw.get(name), 1e-12);
        }

        // 3. Bucketed Temporal PageRank with TemporalPageRank.bucketMillis
        final TinkerGraph oltpGraph3 = buildTemporalPageRankGraph();
        final TinkerGraph olapGraph3 = buildTemporalPageRankGraph();
        final Map<String, Double> oltpBucketed = collectRanks(oltpGraph3.traversal().V()
                .temporalPageRank()
                .with(TemporalPageRank.bucketMillis, 2000L)
                .with(TemporalPageRank.propertyName, "btpr"), "btpr");
        final Map<String, Double> olapBucketed = collectRanks(olapGraph3.traversal().withComputer().V()
                .temporalPageRank()
                .with(TemporalPageRank.bucketMillis, 2000L)
                .with(TemporalPageRank.propertyName, "btpr"), "btpr");

        assertEquals(oltpBucketed.keySet(), olapBucketed.keySet());
        for (final String name : oltpBucketed.keySet()) {
            assertEquals("Bucketed Temporal PageRank mismatch for " + name,
                    oltpBucketed.get(name), olapBucketed.get(name), 1e-12);
        }
    }

    @Test
    public void shouldMatchOltpAndOlapTemporalPageRankInWindowAndScopedSubgraph() {
        // 1. Window clipping: edge a->b [1000, 3000) clipped to [1500, 2500), future edges excluded
        final TinkerGraph oltpWinGraph = buildTemporalPageRankGraph();
        final TinkerGraph olapWinGraph = buildTemporalPageRankGraph();

        final Map<String, Double> oltpWin = collectRanks(oltpWinGraph.traversal().V()
                .window(1500L, 4500L)
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "wtpr"), "wtpr");
        final Map<String, Double> olapWin = collectRanks(olapWinGraph.traversal().withComputer().V()
                .window(1500L, 4500L)
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "wtpr"), "wtpr");

        assertEquals(oltpWin.keySet(), olapWin.keySet());
        for (final String name : oltpWin.keySet()) {
            assertEquals("Windowed Temporal PageRank mismatch for " + name,
                    oltpWin.get(name), olapWin.get(name), 1e-12);
        }

        // 2. Scoped subgraph via has("group", "A")
        final TinkerGraph oltpScopeGraph = buildTemporalPageRankGraph();
        final TinkerGraph olapScopeGraph = buildTemporalPageRankGraph();

        final Map<String, Double> oltpScoped = collectRanks(oltpScopeGraph.traversal().V()
                .has("group", "A")
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "stpr"), "stpr");
        final Map<String, Double> olapScoped = collectRanks(olapScopeGraph.traversal().withComputer().V()
                .has("group", "A")
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "stpr"), "stpr");

        assertEquals(oltpScoped.keySet(), olapScoped.keySet());
        for (final String name : oltpScoped.keySet()) {
            assertEquals("Scoped Temporal PageRank mismatch for " + name,
                    oltpScoped.get(name), olapScoped.get(name), 1e-12);
        }
    }

    @Test
    public void shouldSupportStandaloneTemporalPageRankVertexProgramBucketingAndGryoPayloads() throws Exception {
        // Standalone execution + bucketing
        final TinkerGraph graph = buildTemporalPageRankGraph();
        final ComputerResult result = graph.compute().program(
                TemporalPageRankVertexProgram.build()
                        .alpha(0.85d)
                        .beta(0.5d)
                        .normalize(true)
                        .bucketMillis(500L)
                        .property("standaloneTpr")
                        .create(graph)).submit().get();

        double sum = 0.0d;
        int count = 0;
        final List<Vertex> vertices = new ArrayList<>();
        result.graph().vertices().forEachRemaining(vertices::add);
        for (final Vertex v : vertices) {
            final double rank = v.<Double>value("standaloneTpr");
            assertTrue(rank >= 0.0d);
            sum += rank;
            count++;
        }
        assertEquals(5, count);
        assertEquals(1.0d, sum, 1e-9);

        // Edge-free graph produces 0.0 on all vertices without dividing by zero
        final TinkerGraph emptyEdges = TinkerGraph.open();
        emptyEdges.traversal().addV("node").property("name", "solo").next();
        final List<Object> soloRanks = emptyEdges.traversal().withComputer().V()
                .temporalPageRank().values(TemporalPageRankVertexProgram.PAGE_RANK).toList();
        assertEquals(1, soloRanks.size());
        assertEquals(0.0d, ((Number) soloRanks.get(0)).doubleValue(), 1e-12);

        // Lifetime.EMPTY activeWindow must stay EMPTY (not become null/unbounded) in standalone OLAP execution
        final TinkerGraph emptyWinGraph = buildTemporalPageRankGraph();
        final ComputerResult emptyWinRes = emptyWinGraph.compute().program(
                TemporalPageRankVertexProgram.build()
                        .activeWindow(Lifetime.EMPTY)
                        .property("emptyWinTpr")
                        .create(emptyWinGraph)).submit().get();
        emptyWinRes.graph().vertices().forEachRemaining(v ->
                assertEquals(0.0d, v.<Double>value("emptyWinTpr"), 1e-12));

        // Semi-bounded (-inf, 2000) edge starting at Long.MIN_VALUE with bucketMillis=500 must NOT overflow
        // epoch ordering: u->v (-inf, 2000) must execute BEFORE v->w [2000, 3000) and transfer active mass!
        final TinkerGraph semiOltp = TinkerGraph.open();
        final TinkerGraph semiOlap = TinkerGraph.open();
        for (final TinkerGraph sg : new TinkerGraph[]{semiOltp, semiOlap}) {
            final GraphTraversalSource sgs = sg.traversal();
            final Vertex su = sgs.addV("node").property("name", "u").next();
            final Vertex sv = sgs.addV("node").property("name", "v").next();
            final Vertex sw = sgs.addV("node").property("name", "w").next();
            sgs.addE("contact").from(su).to(sv).property(Lifetime.END_TIME, 2000L).next(); // [Long.MIN_VALUE, 2000)
            sgs.addE("contact").from(sv).to(sw).lifetime(2000L, 3000L).next();
        }
        final Map<String, Double> semiOltpRanks = collectRanks(semiOltp.traversal().V()
                .temporalPageRank().with(TemporalPageRank.propertyName, "st"), "st");
        final Map<String, Double> semiOlapRanks = collectRanks(semiOlap.traversal().withComputer().V()
                .temporalPageRank()
                .with(TemporalPageRank.bucketMillis, 500L)
                .with(TemporalPageRank.propertyName, "st"), "st");
        assertEquals(semiOltpRanks.keySet(), semiOlapRanks.keySet());
        for (final String name : semiOltpRanks.keySet()) {
            assertEquals("Semi-bounded Long.MIN_VALUE bucketMillis mismatch for " + name,
                    semiOltpRanks.get(name), semiOlapRanks.get(name), 1e-12);
        }

        // Simultaneous zero-duration point edges [t, t] (including self-loop and atTime snapshot) must be
        // order-independent across vertices and match OLTP == OLAP to 1e-12!
        final TinkerGraph pointOltp = TinkerGraph.open();
        final TinkerGraph pointOlap = TinkerGraph.open();
        for (final TinkerGraph pg : new TinkerGraph[]{pointOltp, pointOlap}) {
            final GraphTraversalSource pgs = pg.traversal();
            final Vertex pa = pgs.addV("node").property("name", "a").next();
            final Vertex pb = pgs.addV("node").property("name", "b").next();
            final Vertex pc = pgs.addV("node").property("name", "c").next();
            pgs.addE("contact").from(pa).to(pb).lifetime(1000L, 1000L).next();
            pgs.addE("contact").from(pb).to(pb).lifetime(1000L, 1000L).next();
            pgs.addE("contact").from(pb).to(pc).lifetime(1000L, 1000L).next();
            pgs.addE("contact").from(pb).to(pc).lifetime(2000L, 3000L).next();
        }
        final Map<String, Double> pointOltpRanks = collectRanks(pointOltp.traversal().V()
                .temporalPageRank().with(TemporalPageRank.propertyName, "pt"), "pt");
        final Map<String, Double> pointOlapRanks = collectRanks(pointOlap.traversal().withComputer().V()
                .temporalPageRank().with(TemporalPageRank.propertyName, "pt"), "pt");
        assertEquals(pointOltpRanks.keySet(), pointOlapRanks.keySet());
        for (final String name : pointOltpRanks.keySet()) {
            assertEquals("Simultaneous point-interval [t, t] OLTP/OLAP mismatch for " + name,
                    pointOltpRanks.get(name), pointOlapRanks.get(name), 1e-12);
        }

        // Custom Lifetime property overwritten with a non-Lifetime value must evict stale IntervalTree entry on commit
        final TinkerTransactionGraph customIdxGraph = TinkerTransactionGraph.open();
        customIdxGraph.createIndex("span", Vertex.class);
        final Vertex cv = customIdxGraph.traversal().addV("item")
                .property("span", Lifetime.from(1000L, 2000L)).next();
        customIdxGraph.tx().commit();
        assertEquals(1, customIdxGraph.traversal().V()
                .has("span", TemporalP.intersects(Lifetime.from(1500L, 2500L))).toList().size());
        customIdxGraph.traversal().V(cv.id()).property("span", "not-a-lifetime").iterate();
        customIdxGraph.tx().commit();
        assertEquals(0, customIdxGraph.traversal().V()
                .has("span", TemporalP.intersects(Lifetime.from(1500L, 2500L))).toList().size());

        // Overwriting custom Lifetime property BEFORE createIndex(...) mid-transaction must record txTemporal tombstone
        final Vertex cv2 = customIdxGraph.traversal().addV("item")
                .property("span2", Lifetime.from(1000L, 2000L)).next();
        customIdxGraph.tx().commit();
        customIdxGraph.traversal().V(cv2.id()).property("span2", "not-a-lifetime").iterate();
        customIdxGraph.createIndex("span2", Vertex.class);
        assertEquals(0, customIdxGraph.traversal().V()
                .has("span2", TemporalP.intersects(Lifetime.from(1500L, 2500L))).toList().size());
        customIdxGraph.tx().commit();
        assertEquals(0, customIdxGraph.traversal().V()
                .has("span2", TemporalP.intersects(Lifetime.from(1500L, 2500L))).toList().size());
        customIdxGraph.tx().rollback();

        // Gryo V3 round-trip verification of all BSP message & state payload types
        final GryoMapper mapper = GryoMapper.build().version(GryoVersion.V3_0).create();
        final GryoWriter writer = GryoWriter.build().mapper(mapper).create();
        final GryoReader reader = GryoReader.build().mapper(mapper).create();

        final Vertex refV = ReferenceFactory.detach(vertices.get(0));
        final Object[] payloads = new Object[]{
                Triplet.with(refV.id(), Lifetime.from(1000L, 2000L), null),
                Triplet.with(0.425d, 0.2125d, 3000L),
                Triplet.with(1000L, 2000L, refV),
                Pair.with(2000L, 0.2125d)
        };
        for (final Object payload : payloads) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeObject(out, payload);
            final Object read = reader.readObject(new ByteArrayInputStream(out.toByteArray()), Object.class);
            assertEquals(payload, read);
        }
    }

    @Test
    public void shouldSupportWherePredicateStepByModulatorWithLiteralTemporalP() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex a = g.addV("item").property("name", "a").property("span", Lifetime.from(1000L, 2000L)).next();
        final Vertex b = g.addV("item").property("name", "b").property("span", Lifetime.from(1500L, 2500L)).next();
        final Vertex c = g.addV("item").property("name", "c").property("span", Lifetime.from(3000L, 4000L)).next();
        g.addE("rel").from(a).to(b).next();
        g.addE("rel").from(a).to(c).next();

        // Single by("span") with literal Lifetime RHS must not run by("span") on the Lifetime literal
        final List<String> overlapping = g.V()
                .where(TemporalP.overlaps(Lifetime.from(1500L, 2500L))).by("span")
                .<String>values("name")
                .toList();
        assertEquals(Collections.singletonList("a"), overlapping);

        // Composed TemporalP + label predicate with two by() modulators
        @SuppressWarnings({"unchecked", "rawtypes"})
        final org.apache.tinkerpop.gremlin.process.traversal.P<String> composedPred =
                (org.apache.tinkerpop.gremlin.process.traversal.P) TemporalP.overlaps(Lifetime.from(1500L, 2500L))
                        .and(org.apache.tinkerpop.gremlin.process.traversal.P.neq("y"));
        final List<String> composed = g.V().as("x").out("rel").as("y")
                .where("x", composedPred)
                .by("span").by("span")
                .select("y").<String>values("name")
                .toList();
        assertEquals(new HashSet<>(Arrays.asList("b", "c")), new HashSet<>(composed));

        // Literal Element RHS is still modulated by by("span")
        final List<String> vsElement = g.V()
                .where(TemporalP.overlaps(b)).by("span")
                .<String>values("name")
                .toList();
        assertEquals(Collections.singletonList("a"), vsElement);
    }

    @Test
    public void shouldRejectCustomDistanceOnTemporalShortestPath() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();
        final Vertex a = g.addV("node").property("name", "a").next();
        final Vertex b = g.addV("node").property("name", "b").next();
        g.addE("link").from(a).to(b).property("weight", 1).lifetime(1000L, 2000L).next();

        try {
            g.withComputer().V().has("name", "a")
                    .temporalShortestPath()
                    .with(org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.ShortestPath.distance, "weight")
                    .toList();
            fail("Expected IllegalArgumentException for custom distance on temporalShortestPath()");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxDistance"));
        }

        try {
            graph.compute().program(
                    org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalShortestPathVertexProgram.build()
                            .distanceProperty("weight")
                            .create(graph)).submit().get();
            fail("Expected IllegalStateException wrapping IllegalArgumentException for custom distanceProperty");
        } catch (final IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
            assertTrue(expected.getMessage().contains("maxDistance"));
        } catch (final Exception other) {
            fail("Expected IllegalStateException, got: " + other);
        }
    }

    private static void createVertexIndex(final AbstractTinkerGraph graph, final String key) {
        if (graph instanceof TinkerGraph) {
            ((TinkerGraph) graph).createIndex(key, Vertex.class);
        } else if (graph instanceof TinkerTransactionGraph) {
            ((TinkerTransactionGraph) graph).createIndex(key, Vertex.class);
        }
    }

    @Test
    public void shouldIndexNonLifetimeTemporalTypesOnCustomKey() {
        for (final AbstractTinkerGraph graph : new AbstractTinkerGraph[]{TinkerGraph.open(), TinkerTransactionGraph.open()}) {
            createVertexIndex(graph, "eventTime");
            final GraphTraversalSource g = graph.traversal();

            g.addV("event").property("name", "dateV").property("eventTime", new Date(1500L)).next();
            g.addV("event").property("name", "instantV").property("eventTime", Instant.ofEpochMilli(2500L)).next();
            g.addV("event").property("name", "longV").property("eventTime", 3500L).next();
            g.addV("event").property("name", "intervalV").property("eventTime", new Lifetime.Interval(4000L, 5000L)).next();
            g.addV("event").property("name", "isoV").property("eventTime", "1970-01-01T00:00:05.500Z").next();

            if (graph.features().graph().supportsTransactions()) {
                assertEquals(new HashSet<>(Arrays.asList("dateV", "instantV")),
                        new HashSet<>(g.V().has("eventTime", TemporalP.intersects(Lifetime.from(1000L, 3000L))).values("name").toList()));
                graph.tx().commit();
            }

            assertEquals(new HashSet<>(Arrays.asList("dateV", "instantV")),
                    new HashSet<>(g.V().has("eventTime", TemporalP.intersects(Lifetime.from(1000L, 3000L))).values("name").toList()));
            assertEquals(new HashSet<>(Arrays.asList("longV", "intervalV", "isoV")),
                    new HashSet<>(g.V().has("eventTime", TemporalP.intersects(Lifetime.from(3000L, 6000L))).values("name").toList()));

            if (graph.features().graph().supportsTransactions()) {
                graph.tx().rollback();
            }
        }
    }

    @Test
    public void shouldSupportMultiValuedCustomIndexedTemporalProperties() {
        for (final AbstractTinkerGraph graph : new AbstractTinkerGraph[]{TinkerGraph.open(), TinkerTransactionGraph.open()}) {
            createVertexIndex(graph, "periods");
            final GraphTraversalSource g = graph.traversal();
            final boolean isTx = graph.features().graph().supportsTransactions();

            final Vertex v = g.addV("shift").property("name", "w1")
                    .property(VertexProperty.Cardinality.list, "periods", Lifetime.from(1000L, 2000L))
                    .property(VertexProperty.Cardinality.list, "periods", Lifetime.from(3000L, 4000L))
                    .next();
            if (isTx) {
                graph.tx().commit();
            }

            assertEquals(Collections.singletonList("w1"),
                    g.V().has("periods", TemporalP.intersects(Lifetime.from(1500L, 1800L))).<String>values("name").toList());
            assertEquals(Collections.singletonList("w1"),
                    g.V().has("periods", TemporalP.intersects(Lifetime.from(3500L, 3800L))).<String>values("name").toList());

            // Adding a non-temporal string to Cardinality.list must preserve both indexed Lifetimes
            g.V(v.id()).property(VertexProperty.Cardinality.list, "periods", "non-temporal-note").iterate();
            assertEquals(Collections.singletonList("w1"),
                    g.V().has("periods", TemporalP.intersects(Lifetime.from(1500L, 1800L))).<String>values("name").toList());
            assertEquals(Collections.singletonList("w1"),
                    g.V().has("periods", TemporalP.intersects(Lifetime.from(3500L, 3800L))).<String>values("name").toList());
            if (isTx) {
                graph.tx().commit();
                assertEquals(Collections.singletonList("w1"),
                        g.V().has("periods", TemporalP.intersects(Lifetime.from(1500L, 1800L))).<String>values("name").toList());
                assertEquals(Collections.singletonList("w1"),
                        g.V().has("periods", TemporalP.intersects(Lifetime.from(3500L, 3800L))).<String>values("name").toList());
            }

            // Removing one Lifetime from Cardinality.list must evict only that Lifetime and keep the remaining one
            g.V(v.id()).properties("periods").hasValue(Lifetime.from(1000L, 2000L)).drop().iterate();
            assertTrue(g.V().has("periods", TemporalP.intersects(Lifetime.from(1500L, 1800L))).toList().isEmpty());
            assertEquals(Collections.singletonList("w1"),
                    g.V().has("periods", TemporalP.intersects(Lifetime.from(3500L, 3800L))).<String>values("name").toList());
            if (isTx) {
                graph.tx().commit();
                assertTrue(g.V().has("periods", TemporalP.intersects(Lifetime.from(1500L, 1800L))).toList().isEmpty());
                assertEquals(Collections.singletonList("w1"),
                        g.V().has("periods", TemporalP.intersects(Lifetime.from(3500L, 3800L))).<String>values("name").toList());
                graph.tx().rollback();
            }
        }
    }

    @Test
    public void shouldPopulateTxIndexOnMidTransactionCreateIndexForTemporalIntervals() {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        g.addV("person").property("name", "committed").lifetime(500L, 900L).next();
        graph.tx().commit();

        // Uncommitted draft in new transaction before createIndex
        g.addV("person").property("name", "draft").lifetime(1000L, 2000L).next();
        graph.createIndex(Lifetime.TEMPORAL_INTERVALS, Vertex.class);

        // Compare.eq lookup on "temporalIntervals" must find the uncommitted draft in the same transaction
        assertEquals(Collections.singletonList("draft"),
                g.V().has(Lifetime.TEMPORAL_INTERVALS, "1000:2000").<String>values("name").toList());
        assertEquals(Collections.singletonList("draft"),
                g.V().temporalOverlaps(Lifetime.from(1500L, 2500L)).<String>values("name").toList());

        graph.tx().commit();
        assertEquals(Collections.singletonList("draft"),
                g.V().has(Lifetime.TEMPORAL_INTERVALS, "1000:2000").<String>values("name").toList());
        graph.tx().rollback();
    }

    @Test
    public void shouldCleanlyRollbackWhenMalformedTemporalIntervalsFailsDuringCommit() {
        final TinkerTransactionGraph graph = TinkerTransactionGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex v1 = g.addV("person").property("name", "v1").lifetime(1000L, 2000L).next();
        graph.tx().commit();

        g.V(v1.id()).property("name", "v1-corrupt").property(Lifetime.TEMPORAL_INTERVALS, "malformed-interval").iterate();
        try {
            graph.tx().commit();
            fail("Expected IllegalArgumentException during commit of malformed temporalIntervals");
        } catch (final IllegalArgumentException expected) {
            // doCommit() must have rolled back and closed the transaction cleanly
        }

        assertFalse(graph.tx().isOpen());
        assertEquals("v1", g.V(v1.id()).values("name").next());
        assertEquals(Lifetime.from(1000L, 2000L), Lifetime.fromProperties(g.V(v1.id()).next()));

        // Subsequent transaction on the same thread must commit cleanly without stale container state
        g.V(v1.id()).property("name", "v1-updated").lifetime(1000L, 3000L).iterate();
        graph.tx().commit();
        assertEquals("v1-updated", g.V(v1.id()).values("name").next());
        assertEquals(Lifetime.from(1000L, 3000L), Lifetime.fromProperties(g.V(v1.id()).next()));
        graph.tx().rollback();
    }

    @Test
    public void shouldAllowSequentialStartTimeThenEndTimeShiftWhenTemporalIntervalsIsIndexed() {
        for (final AbstractTinkerGraph graph : new AbstractTinkerGraph[]{TinkerGraph.open(), TinkerTransactionGraph.open()}) {
            createVertexIndex(graph, Lifetime.TEMPORAL_INTERVALS);
            final GraphTraversalSource g = graph.traversal();
            final boolean isTx = graph.features().graph().supportsTransactions();

            final Vertex v = g.addV("event").property("name", "e1")
                    .property(Lifetime.START_TIME, 1000L)
                    .property(Lifetime.END_TIME, 2000L)
                    .next();
            if (isTx) {
                graph.tx().commit();
            }

            // Shift [1000, 2000) -> [3000, 4000) sequentially via startTime first (transiently 3000 > 2000), then endTime
            g.V(v.id()).property(Lifetime.START_TIME, 3000L).iterate();
            g.V(v.id()).property(Lifetime.END_TIME, 4000L).iterate();

            assertTrue(g.V().temporalIntersects(Lifetime.from(1000L, 2000L)).toList().isEmpty());
            assertEquals(Collections.singletonList("e1"),
                    g.V().temporalIntersects(Lifetime.from(3000L, 4000L)).<String>values("name").toList());

            if (isTx) {
                graph.tx().commit();
                assertTrue(g.V().temporalIntersects(Lifetime.from(1000L, 2000L)).toList().isEmpty());
                assertEquals(Collections.singletonList("e1"),
                        g.V().temporalIntersects(Lifetime.from(3000L, 4000L)).<String>values("name").toList());
                graph.tx().rollback();
            }
        }
    }

    @Test
    public void shouldSupportOlapTemporalPageRankAfterBarrierAndVertexIdScopingAndEmptyWindow() {
        // 1. OLAP temporalPageRank() after a barrier step: V().order().by("name").limit(2).temporalPageRank()
        final TinkerGraph oltpBarrierGraph = buildTemporalPageRankGraph();
        final TinkerGraph olapBarrierGraph = buildTemporalPageRankGraph();
        final Map<String, Double> oltpBarrier = collectRanks(oltpBarrierGraph.traversal().V()
                .order().by("name").limit(2)
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "btpr"), "btpr");
        final Map<String, Double> olapBarrier = collectRanks(olapBarrierGraph.traversal().withComputer().V()
                .order().by("name").limit(2)
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "btpr"), "btpr");
        assertEquals(oltpBarrier.keySet(), olapBarrier.keySet());
        for (final String name : oltpBarrier.keySet()) {
            assertEquals("Barrier-scoped Temporal PageRank mismatch for " + name,
                    oltpBarrier.get(name), olapBarrier.get(name), 1e-12);
        }

        // 2. V(v1, v2).temporalPageRank() subgraph scoping parity between OLTP and OLAP
        final TinkerGraph oltpIdGraph = buildTemporalPageRankGraph();
        final TinkerGraph olapIdGraph = buildTemporalPageRankGraph();
        final Object oltpA = oltpIdGraph.traversal().V().has("name", "a").id().next();
        final Object oltpB = oltpIdGraph.traversal().V().has("name", "b").id().next();
        final Object olapA = olapIdGraph.traversal().V().has("name", "a").id().next();
        final Object olapB = olapIdGraph.traversal().V().has("name", "b").id().next();
        final Map<String, Double> oltpById = collectRanks(oltpIdGraph.traversal().V(oltpA, oltpB)
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "idtpr"), "idtpr");
        final Map<String, Double> olapById = collectRanks(olapIdGraph.traversal().withComputer().V(olapA, olapB)
                .temporalPageRank()
                .with(TemporalPageRank.propertyName, "idtpr"), "idtpr");
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), olapById.keySet());
        assertEquals(oltpById.keySet(), olapById.keySet());
        for (final String name : oltpById.keySet()) {
            assertEquals("ID-scoped Temporal PageRank mismatch for " + name,
                    oltpById.get(name), olapById.get(name), 1e-12);
        }

        // 3. g.withComputer().V().window(Lifetime.EMPTY) with untimed vertices must return empty
        final TinkerGraph untimedGraph = TinkerGraph.open();
        final Vertex u1 = untimedGraph.traversal().addV("node").property("name", "u1").next();
        final Vertex u2 = untimedGraph.traversal().addV("node").property("name", "u2").next();
        untimedGraph.traversal().addE("link").from(u1).to(u2).next();
        assertTrue(untimedGraph.traversal().V().window(Lifetime.EMPTY).toList().isEmpty());
        assertTrue(untimedGraph.traversal().withComputer().V().window(Lifetime.EMPTY).toList().isEmpty());
        assertTrue(untimedGraph.traversal().withComputer().V().window(Lifetime.EMPTY).temporalPageRank().toList().isEmpty());
    }

    private static TinkerGraph buildTemporalPageRankGraph() {
        final TinkerGraph graph = TinkerGraph.open();
        final GraphTraversalSource g = graph.traversal();

        final Vertex a = g.addV("node").property("name", "a").property("group", "A").lifetime(1000L, 9000L).next();
        final Vertex b = g.addV("node").property("name", "b").property("group", "A").lifetime(1000L, 9000L).next();
        final Vertex c = g.addV("node").property("name", "c").property("group", "A").lifetime(1000L, 9000L).next();
        final Vertex d = g.addV("node").property("name", "d").property("group", "B").lifetime(2000L, 8000L).next();
        final Vertex isolated = g.addV("node").property("name", "iso").property("group", "A").lifetime(1000L, 9000L).next();

        // Multi-interval edge a->b, simultaneous start timestamps (a->b and a->c at 1000), causal chain a->b->c->d, self-loop b->b
        g.addE("contact").from(a).to(b).lifetime(1000L, 2000L).addInterval(5000L, 6000L).next();
        g.addE("contact").from(a).to(c).lifetime(1000L, 2500L).next();
        g.addE("contact").from(b).to(c).lifetime(2000L, 3000L).next();
        g.addE("contact").from(b).to(b).lifetime(3000L, 3500L).next();
        g.addE("contact").from(c).to(d).lifetime(3500L, 4500L).next();
        g.addE("contact").from(d).to(a).lifetime(6000L, 7000L).next();

        // keep isolated referenced cleanly
        assertNotNull(isolated);
        return graph;
    }

    @Test
    public void shouldSupportMatchWithLiteralTemporalPAndDeterministicShortestPathAndPostOlapWindow() {
        // 1. match(...).where(TemporalP.overlaps(literalLifetime)) must not drop null sentinel in WherePredicateStep
        final TinkerGraph matchGraph = TinkerGraph.open();
        final GraphTraversalSource mg = matchGraph.traversal();
        final Vertex m1 = mg.addV("item").property("name", "m1").lifetime(1000L, 2000L).next();
        final Vertex m2 = mg.addV("item").property("name", "m2").lifetime(1500L, 2500L).next();
        mg.addE("links").from(m1).to(m2).lifetime(1500L, 1800L).next();
        final List<Object> matched = mg.V().match(
                org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.as("a").out("links").as("b"),
                org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.as("b").where(TemporalP.overlaps(Lifetime.from(1600L, 3000L)))
        ).select("b").values("name").toList();
        assertEquals(Collections.singletonList("m2"), matched);

        final List<Object> matchedFolded = mg.V().match(
                org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.as("a").out("links").as("b")
        ).where("b", TemporalP.overlaps(Lifetime.from(1600L, 3000L))).select("b").values("name").toList();
        assertEquals(Collections.singletonList("m2"), matchedFolded);

        // 2. TemporalShortestPathVertexProgram EARLIEST_DEPARTURE with two tied-causal-time intermediate paths
        final TinkerGraph spGraph = TinkerGraph.open();
        final GraphTraversalSource sg = spGraph.traversal();
        final Vertex a = sg.addV("node").property("name", "A").next();
        final Vertex b = sg.addV("node").property("name", "B").next();
        final Vertex x = sg.addV("node").property("name", "X").next();
        final Vertex y = sg.addV("node").property("name", "Y").next();
        final Vertex c = sg.addV("node").property("name", "C").next();
        sg.addE("e").from(a).to(b).lifetime(100L, 500L).next();
        sg.addE("e").from(a).to(x).lifetime(110L, 120L).next();
        sg.addE("e").from(x).to(b).lifetime(120L, 200L).next();
        sg.addE("e").from(a).to(y).lifetime(115L, 130L).next();
        sg.addE("e").from(y).to(b).lifetime(130L, 200L).next();
        sg.addE("e").from(b).to(c).lifetime(250L, 300L).next();

        final List<org.apache.tinkerpop.gremlin.process.traversal.Path> pathsToC = sg.withComputer().V(a.id())
                .temporalShortestPath(org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalPathTarget.EARLIEST_DEPARTURE, 0L)
                .with(org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalShortestPathVertexProgram.EDGE_TRAVERSAL,
                        org.apache.tinkerpop.gremlin.structure.Direction.OUT)
                .with(org.apache.tinkerpop.gremlin.process.computer.search.path.TemporalShortestPathVertexProgram.TARGET_VERTEX_FILTER,
                        org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.has("name", "C"))
                .toList();
        assertEquals("Both tied-causal-time paths [A,X,B,C] and [A,Y,B,C] must be found", 2, pathsToC.size());

        // 3. TemporalGraphViewStrategy preserves active window on trailing TraversalVertexProgramStep after OLAP step
        final TinkerGraph postOlapGraph = TinkerGraph.open();
        final GraphTraversalSource pg = postOlapGraph.traversal();
        final Vertex p1 = pg.addV("person").property("name", "p1").lifetime(1000L, 5000L).next();
        final Vertex p2 = pg.addV("person").property("name", "p2").lifetime(1000L, 5000L).next();
        final Vertex p3 = pg.addV("person").property("name", "p3").lifetime(1000L, 5000L).next();
        pg.addE("knows").from(p1).to(p2).lifetime(1000L, 2000L).next();
        pg.addE("knows").from(p1).to(p3).lifetime(3000L, 4000L).next();
        final List<Object> postOlapNeighbors = pg.withComputer().V()
                .window(1000L, 2000L)
                .temporalPageRank()
                .out("knows")
                .values("name")
                .toList();
        assertEquals(Collections.singletonList("p2"), postOlapNeighbors);
    }

    private static Map<String, Double> collectRanks(final GraphTraversal<Vertex, Vertex> traversal, final String prop) {
        final List<Map<String, Object>> rows = traversal
                .project("name", "rank").by("name").by(prop)
                .toList();
        final Map<String, Double> out = new HashMap<>();
        for (final Map<String, Object> row : rows) {
            out.put((String) row.get("name"), ((Number) row.get("rank")).doubleValue());
        }
        return out;
    }
}
