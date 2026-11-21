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
package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.AbstractThreadLocalTransaction;
import org.apache.tinkerpop.gremlin.structure.util.TransactionException;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementation of {@link AbstractThreadLocalTransaction} for {@link TinkerTransactionGraph}
 */
final class TinkerTransaction extends AbstractThreadLocalTransaction {

    private static final String TX_CONFLICT = "Conflict: element modified in another transaction";
    private static final String TEMPORAL_TX_CONFLICT = "Conflict: temporal referential integrity violated by concurrent transaction";

    /**
     * Initial value of transaction number.
     */
    private static final long NOT_STARTED = -1;

    /**
     * Counter for opened transactions. Used to get unique id for each new transaction.
     */
    private static final AtomicLong openedTx;

    /**
     * Unique number of transaction for each thread.
     */
    private final ThreadLocal<Long> txNumber = ThreadLocal.withInitial(() -> NOT_STARTED);

    /**
     * Monotonic graph-level commit sequence incremented on each committed transaction.
     */
    private final AtomicLong commitSequence = new AtomicLong(0L);

    /**
     * Coordinates commit finalization (element commit, lastCommitSeq, and index commit) with transaction open
     * so concurrent transactions never observe a torn commitSequence or torn element/index visibility.
     */
    final ReentrantReadWriteLock commitVisibilityLock = new ReentrantReadWriteLock();

    /**
     * Commit sequence observed by the current thread when its transaction opened.
     */
    private final ThreadLocal<Long> txStartSequence = ThreadLocal.withInitial(() -> NOT_STARTED);

    /**
     * Set of references to vertex containers changed in current transaction.
     */
    private final ThreadLocal<Set<TinkerElementContainer<TinkerVertex>>> txChangedVertices = new ThreadLocal<>();

    /**
     * Set of references to edge containers changed in current transaction.
     */
    private final ThreadLocal<Set<TinkerElementContainer<TinkerEdge>>> txChangedEdges = new ThreadLocal<>();

    /**
     * Set of references to element containers read in current transaction.
     */
    private final ThreadLocal<Set<TinkerElementContainer>> txReadElements = new ThreadLocal<>();

    private final TinkerTransactionGraph graph;

    static {
        openedTx = new AtomicLong(0);
    }

    public TinkerTransaction(final TinkerTransactionGraph g) {
        super(g);
        graph = g;
    }

    @Override
    public boolean isOpen() {
        return txNumber.get() != NOT_STARTED;
    }

    @Override
    public <T extends TraversalSource> T begin() {
        doOpen();
        return super.begin();
    }

    @Override
    protected void doOpen() {
        txNumber.set(openedTx.getAndIncrement());
        commitVisibilityLock.readLock().lock();
        try {
            txStartSequence.set(commitSequence.get());
        } finally {
            commitVisibilityLock.readLock().unlock();
        }
    }

    private void ensureTxOpen() {
        if (!isOpen()) {
            doOpen();
        } else if (txStartSequence.get() == NOT_STARTED) {
            commitVisibilityLock.readLock().lock();
            try {
                txStartSequence.set(commitSequence.get());
            } finally {
                commitVisibilityLock.readLock().unlock();
            }
        }
    }

    protected long getTxNumber() {
        ensureTxOpen();
        return txNumber.get();
    }

    /**
     * Adds element to list of changes in current transaction.
     */
    protected <T extends TinkerElement> void markChanged(TinkerElementContainer<T> container) {
        ensureTxOpen();

        T element = container.getUnmodified();
        if (null == element) element = container.getModified();
        if (element instanceof TinkerVertex) {
            if (null == txChangedVertices.get())
                txChangedVertices.set(new HashSet<>());
            txChangedVertices.get().add((TinkerElementContainer<TinkerVertex>) container);
        } else {
            if (null == txChangedEdges.get())
                txChangedEdges.set(new HashSet<>());
            txChangedEdges.get().add((TinkerElementContainer<TinkerEdge>) container);
        }
    }

    /**
     * Adds element to list of read in current transaction.
     */
    protected <T extends TinkerElement> void markRead(TinkerElementContainer container) {
        ensureTxOpen();

        if (null == txReadElements.get())
            txReadElements.set(new HashSet<>());

        txReadElements.get().add(container);
    }

    /**
     * Try to commit all changes made in current transaction.
     * Workflow:
     * 1. collect all changes
     * 2. verify if any elements already changed, throw {@link TransactionException} if any
     * 3. try to lock all containers to prevent other tx from making changes
     * 4. one more time verify elements versions
     * 5. update indices
     * 6. commit all changes
     * On {@link TransactionException}:
     *  rollback all changes
     * Lastly:
     *  cleanup transaction intermediate variables.
     *
     * @throws TransactionException
     */
    @Override
    protected void doCommit() throws TransactionException {
        final long txVersion = txNumber.get();

        // collect all changes
        Set<TinkerElementContainer<TinkerVertex>> changedVertices = txChangedVertices.get();
        if (null == changedVertices) changedVertices = Collections.emptySet();
        Set<TinkerElementContainer<TinkerEdge>> changedEdges = txChangedEdges.get();
        if (null == changedEdges) changedEdges = Collections.emptySet();

        final Set<TinkerElementContainer<?>> extraLocked = new LinkedHashSet<>();

        try {
            // Double-checked locking to reduce lock time
            if (changedVertices.stream().anyMatch(v -> v.updatedOutsideTransaction()) ||
                    changedEdges.stream().anyMatch(v -> v.updatedOutsideTransaction()))
                throw new TransactionException(TX_CONFLICT);

            // try to lock all element containers, throw exception if any element already locked by other tx
            changedVertices.forEach(v -> {
                if (!v.tryLock())
                    throw new TransactionException(TX_CONFLICT);
            });
            changedEdges.forEach(e -> {
                if (!e.tryLock())
                    throw new TransactionException(TX_CONFLICT);
            });

            // verify versions of all elements to be sure no element changes during setting lock
            if (changedVertices.stream().anyMatch(v -> v.updatedOutsideTransaction()) ||
                    changedEdges.stream().anyMatch(e -> e.updatedOutsideTransaction()))
                throw new TransactionException(TX_CONFLICT);

            // Prevent temporal write-skew across concurrent (vertex, incident edge) lifetime mutations
            verifyTemporalReferentialIntegrity(changedVertices, changedEdges, extraLocked);

            commitVisibilityLock.writeLock().lock();
            try {
                // update indices
                final TinkerTransactionalIndex vertexIndex = (TinkerTransactionalIndex) graph.vertexIndex;
                if (vertexIndex != null) vertexIndex.commit(changedVertices);
                final TinkerTransactionalIndex edgeIndex = (TinkerTransactionalIndex) graph.edgeIndex;
                if (edgeIndex != null) edgeIndex.commit(changedEdges);

                // commit all changes
                final long commitSeq = commitSequence.incrementAndGet();
                changedVertices.forEach(v -> {
                    v.commit(txVersion);
                    v.setLastCommitSeq(commitSeq);
                });
                changedEdges.forEach(e -> {
                    e.commit(txVersion);
                    e.setLastCommitSeq(commitSeq);
                });
            } finally {
                commitVisibilityLock.writeLock().unlock();
            }
        } catch (final Throwable ex) {
            // rollback on error
            changedVertices.forEach(v -> v.rollback());
            changedEdges.forEach(e -> e.rollback());

            // also revert indices update
            final TinkerTransactionalIndex vertexIndex = (TinkerTransactionalIndex) graph.vertexIndex;
            if (vertexIndex != null) vertexIndex.rollback();
            final TinkerTransactionalIndex edgeIndex = (TinkerTransactionalIndex) graph.edgeIndex;
            if (edgeIndex != null) edgeIndex.rollback();

            if (ex instanceof TransactionException) {
                throw (TransactionException) ex;
            }
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            if (ex instanceof Error) {
                throw (Error) ex;
            }
            throw new TransactionException(ex.getMessage(), ex);
        } finally {
            // remove elements from graph if not used in other tx's
            changedVertices.stream().filter(v -> v.canBeRemoved()).forEach(v -> graph.getVertices().remove(v.getElementId()));
            changedEdges.stream().filter(e -> e.canBeRemoved()).forEach(e -> graph.getEdges().remove(e.getElementId()));

            final Set<TinkerElementContainer> readElements = txReadElements.get();
            if (readElements != null)
                readElements.stream().forEach(e -> e.reset());

            txChangedVertices.remove();
            txChangedEdges.remove();
            txReadElements.remove();

            extraLocked.forEach(TinkerElementContainer::releaseLock);
            changedVertices.forEach(v -> v.releaseLock());
            changedEdges.forEach(e -> e.releaseLock());

            txNumber.set(NOT_STARTED);
            txStartSequence.set(NOT_STARTED);
        }
    }

    /**
     * Guards against temporal write-skew across concurrent transactions (e.g., tx1 narrowing a vertex's lifetime
     * while tx2 widens an existing incident edge's lifetime or narrows the opposite endpoint of an untimed edge).
     * Zero-overhead fast-path when no temporal lifetime properties changed in this transaction.
     */
    private void verifyTemporalReferentialIntegrity(
            final Set<TinkerElementContainer<TinkerVertex>> changedVertices,
            final Set<TinkerElementContainer<TinkerEdge>> changedEdges,
            final Set<TinkerElementContainer<?>> extraLocked) throws TransactionException {

        final Set<TinkerElementContainer<TinkerVertex>> temporalVertices = new LinkedHashSet<>();
        for (final TinkerElementContainer<TinkerVertex> vc : changedVertices) {
            if (!vc.isDeleted() && hasTemporalLifetimeChange(vc)) {
                temporalVertices.add(vc);
            }
        }
        final Set<TinkerElementContainer<TinkerEdge>> temporalEdges = new LinkedHashSet<>();
        for (final TinkerElementContainer<TinkerEdge> ec : changedEdges) {
            if (!ec.isDeleted() && hasTemporalLifetimeChange(ec)) {
                temporalEdges.add(ec);
            }
        }
        if (temporalVertices.isEmpty() && temporalEdges.isEmpty()) {
            return;
        }

        // 1. Verify temporally-modified edges against their endpoint vertices
        for (final TinkerElementContainer<TinkerEdge> ec : temporalEdges) {
            final TinkerEdge edge = ec.getModified();
            if (edge == null) {
                continue;
            }
            if (!Lifetime.hasLifetimeProperties(edge)) {
                verifyUntimedEdgeEndpoints(edge.outVertexId, edge.inVertexId, changedVertices, extraLocked);
                continue;
            }
            final Lifetime edgeLt = Lifetime.fromProperties(edge);
            verifyEdgeEndpoint(edge.outVertexId, edgeLt, changedVertices, extraLocked);
            verifyEdgeEndpoint(edge.inVertexId, edgeLt, changedVertices, extraLocked);
        }

        // 2. Verify temporally-modified vertices against their incident edges and opposite endpoints
        for (final TinkerElementContainer<TinkerVertex> vc : temporalVertices) {
            final TinkerVertex modifiedVertex = vc.getModified();
            if (modifiedVertex == null || !Lifetime.hasLifetimeProperties(modifiedVertex)) {
                continue;
            }
            final Lifetime vertexLt = Lifetime.fromProperties(modifiedVertex);
            final Set<Object> incidentEdgeIds = new LinkedHashSet<>();
            collectIncidentEdgeIds(modifiedVertex, incidentEdgeIds);
            collectIncidentEdgeIds(vc.getUnmodified(), incidentEdgeIds);

            for (final Object edgeId : incidentEdgeIds) {
                final TinkerElementContainer<TinkerEdge> ec = graph.getEdges().get(edgeId);
                if (ec == null || ec.isDeleted()) {
                    continue;
                }
                if (!changedEdges.contains(ec) && !extraLocked.contains(ec)) {
                    if (!ec.tryLock()) {
                        throw new TransactionException(TX_CONFLICT);
                    }
                    extraLocked.add(ec);
                }
                if (ec.isDeleted()) {
                    continue;
                }
                final TinkerEdge edge = changedEdges.contains(ec) ? ec.getModified() : ec.getUnmodified();
                if (edge == null || edge.removed) {
                    continue;
                }
                final Object otherId = Objects.equals(edge.outVertexId, modifiedVertex.id()) ? edge.inVertexId : edge.outVertexId;
                final TinkerElementContainer<TinkerVertex> otherVc = Objects.equals(otherId, modifiedVertex.id())
                        ? vc : graph.getVertices().get(otherId);
                if (otherVc != null && !otherVc.equals(vc) && !changedVertices.contains(otherVc) && !extraLocked.contains(otherVc)) {
                    if (!otherVc.tryLock()) {
                        throw new TransactionException(TX_CONFLICT);
                    }
                    extraLocked.add(otherVc);
                }

                final boolean edgeConcurrentlyModified = !changedEdges.contains(ec) && isConcurrentlyModified(ec);
                final boolean otherConcurrentlyModified = otherVc != null && !otherVc.equals(vc)
                        && !changedVertices.contains(otherVc) && isConcurrentlyModified(otherVc);
                if (!edgeConcurrentlyModified && !otherConcurrentlyModified) {
                    continue;
                }

                if (otherVc != null && otherVc.isDeleted()) {
                    throw new TransactionException(TEMPORAL_TX_CONFLICT);
                }
                final TinkerVertex otherV = (otherVc == null || otherVc.equals(vc))
                        ? modifiedVertex
                        : (changedVertices.contains(otherVc) ? otherVc.getModified() : otherVc.getUnmodified());
                final Lifetime maxAllowed = (otherV != null && !otherV.equals(modifiedVertex) && Lifetime.hasLifetimeProperties(otherV))
                        ? vertexLt.intersection(Lifetime.fromProperties(otherV))
                        : vertexLt;

                if (!Lifetime.hasLifetimeProperties(edge)) {
                    if (maxAllowed.isEmpty()) {
                        throw new TransactionException(TEMPORAL_TX_CONFLICT);
                    }
                } else if (!maxAllowed.contains(Lifetime.fromProperties(edge))) {
                    throw new TransactionException(TEMPORAL_TX_CONFLICT);
                }
            }
        }
    }

    private void verifyEdgeEndpoint(
            final Object vertexId,
            final Lifetime edgeLt,
            final Set<TinkerElementContainer<TinkerVertex>> changedVertices,
            final Set<TinkerElementContainer<?>> extraLocked) throws TransactionException {
        if (vertexId == null) {
            return;
        }
        final TinkerElementContainer<TinkerVertex> vc = graph.getVertices().get(vertexId);
        if (vc == null) {
            throw new TransactionException(TEMPORAL_TX_CONFLICT);
        }
        if (!changedVertices.contains(vc) && !extraLocked.contains(vc)) {
            if (!vc.tryLock()) {
                throw new TransactionException(TX_CONFLICT);
            }
            extraLocked.add(vc);
        }
        if (vc.isDeleted()) {
            throw new TransactionException(TEMPORAL_TX_CONFLICT);
        }
        if (!changedVertices.contains(vc) && isConcurrentlyModified(vc)) {
            final TinkerVertex committedV = vc.getUnmodified();
            if (committedV != null && Lifetime.hasLifetimeProperties(committedV)
                    && !Lifetime.fromProperties(committedV).contains(edgeLt)) {
                throw new TransactionException(TEMPORAL_TX_CONFLICT);
            }
        }
    }

    private void verifyUntimedEdgeEndpoints(
            final Object outVertexId,
            final Object inVertexId,
            final Set<TinkerElementContainer<TinkerVertex>> changedVertices,
            final Set<TinkerElementContainer<?>> extraLocked) throws TransactionException {
        if (outVertexId == null || inVertexId == null) {
            return;
        }
        final TinkerElementContainer<TinkerVertex> outVc = graph.getVertices().get(outVertexId);
        final TinkerElementContainer<TinkerVertex> inVc = Objects.equals(outVertexId, inVertexId)
                ? outVc : graph.getVertices().get(inVertexId);
        if (outVc == null || inVc == null) {
            throw new TransactionException(TEMPORAL_TX_CONFLICT);
        }
        for (final TinkerElementContainer<TinkerVertex> vc : new TinkerElementContainer[]{outVc, inVc}) {
            if (!changedVertices.contains(vc) && !extraLocked.contains(vc)) {
                if (!vc.tryLock()) {
                    throw new TransactionException(TX_CONFLICT);
                }
                extraLocked.add(vc);
            }
            if (vc.isDeleted()) {
                throw new TransactionException(TEMPORAL_TX_CONFLICT);
            }
        }
        final boolean outConcurrent = !changedVertices.contains(outVc) && isConcurrentlyModified(outVc);
        final boolean inConcurrent = !changedVertices.contains(inVc) && isConcurrentlyModified(inVc);
        if (!outConcurrent && !inConcurrent) {
            return;
        }
        final TinkerVertex outV = changedVertices.contains(outVc) ? outVc.getModified() : outVc.getUnmodified();
        final TinkerVertex inV = changedVertices.contains(inVc) ? inVc.getModified() : inVc.getUnmodified();
        if (outV != null && inV != null
                && Lifetime.hasLifetimeProperties(outV) && Lifetime.hasLifetimeProperties(inV)
                && Lifetime.fromProperties(outV).intersection(Lifetime.fromProperties(inV)).isEmpty()) {
            throw new TransactionException(TEMPORAL_TX_CONFLICT);
        }
    }

    private boolean isConcurrentlyModified(final TinkerElementContainer<?> container) {
        if (container.updatedOutsideTransaction()) {
            return true;
        }
        final long startSeq = txStartSequence.get();
        return startSeq != NOT_STARTED && container.getLastCommitSeq() > startSeq;
    }

    private static <T extends TinkerElement> boolean hasTemporalLifetimeChange(final TinkerElementContainer<T> container) {
        final T modified = container.getModified();
        if (modified == null) {
            return false;
        }
        final T unmodified = container.getUnmodified();
        final boolean modHas = Lifetime.hasLifetimeProperties(modified);
        final boolean unmodHas = unmodified != null && Lifetime.hasLifetimeProperties(unmodified);
        if (!modHas && !unmodHas) {
            return false;
        }
        if (modHas != unmodHas) {
            return true;
        }
        return !Objects.equals(Lifetime.getLifetimeFromProperties(unmodified), Lifetime.getLifetimeFromProperties(modified));
    }

    private static void collectIncidentEdgeIds(final TinkerVertex vertex, final Set<Object> out) {
        if (vertex == null) {
            return;
        }
        if (vertex.outEdgesId != null) {
            for (final Set<Object> ids : vertex.outEdgesId.values()) {
                out.addAll(ids);
            }
        }
        if (vertex.inEdgesId != null) {
            for (final Set<Object> ids : vertex.inEdgesId.values()) {
                out.addAll(ids);
            }
        }
    }

    /**
     * Rollback all changes made in current transaction.
     * Workflow:
     * 1. Rollback all changes. Lock is not needed here because only this thread have access to data.
     * 2. Rollback indices changes, should be safe.
     * 3. Cleanup transaction intermediate variables.
     *
     * @throws TransactionException
     */
    @Override
    protected void doRollback() throws TransactionException {
        // rollback for all changed elements
        Set<TinkerElementContainer<TinkerVertex>> changedVertices = txChangedVertices.get();
        if (null != changedVertices) changedVertices.forEach(v -> v.rollback());
        Set<TinkerElementContainer<TinkerEdge>> changedEdges = txChangedEdges.get();
        if (null != changedEdges) changedEdges.forEach(e -> e.rollback());

        // rollback indices
        final TinkerTransactionalIndex vertexIndex = (TinkerTransactionalIndex) graph.vertexIndex;
        if (vertexIndex != null) vertexIndex.rollback();
        final TinkerTransactionalIndex edgeIndex = (TinkerTransactionalIndex) graph.edgeIndex;
        if (edgeIndex != null) edgeIndex.rollback();

        // cleanup unused containers
        if (null != changedVertices)
            changedVertices.stream().filter(v -> v.canBeRemoved()).forEach(v -> graph.getVertices().remove(v.getElementId()));
        if (null != changedEdges)
            changedEdges.stream().filter(e -> e.canBeRemoved()).forEach(e -> graph.getEdges().remove(e.getElementId()));

        final Set<TinkerElementContainer> readElements = txReadElements.get();
        if (readElements != null)
            readElements.stream().forEach(e -> e.reset());

        txChangedVertices.remove();
        txChangedEdges.remove();
        txReadElements.remove();

        txNumber.set(NOT_STARTED);
        txStartSequence.set(NOT_STARTED);
    }
}
