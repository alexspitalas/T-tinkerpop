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

package org.apache.tinkerpop.gremlin.process.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalUtil;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime.DropMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

public class LifetimeStep<S> extends AbstractStep<S, S> implements  TraversalParent {
    public enum LifetimeMode {
        REPLACE,
        ADD,
        DROP
    }

    private LifetimeMode mode = LifetimeMode.REPLACE;
    private final DropMode dropMode;
    private Lifetime lifetime;
    private Traversal.Admin<S, ?> lifetimeTraversal;
    private Object startTime;
    private Object endTime;
    private Traversal.Admin<S, ?> startTimeTraversal;
    private Traversal.Admin<S, ?> endTimeTraversal;
    private boolean useTemporalParameters;
    private final String propertyKey;
    private final String propertyValue;

    @SuppressWarnings("unchecked")
    public LifetimeStep(final Traversal.Admin traversal, final Object lifetime,
            final String propertyKey, final String propertyValue) {
        super(traversal);
        this.mode = LifetimeMode.REPLACE;
        this.dropMode = DropMode.SOFT;

        if (lifetime instanceof Lifetime) {
            this.lifetime = (Lifetime) lifetime;
        } else if (lifetime instanceof Traversal) {
            this.lifetimeTraversal = this.integrateChild(((Traversal<S, ?>) lifetime).asAdmin());
        } else {
            throw new IllegalArgumentException("Unsupported lifetime parameter type: " +
                    (lifetime == null ? "null" : lifetime.getClass().getName()));
        }

        this.propertyKey = propertyKey;
        this.propertyValue = propertyValue;
    }

    @SuppressWarnings("unchecked")
    public LifetimeStep(final Traversal.Admin traversal, final Object startTime, final Object endTime,
            final String propertyKey, final String propertyValue, final LifetimeMode mode, final DropMode dropMode) {
        super(traversal);
        this.mode = mode != null ? mode : LifetimeMode.REPLACE;
        this.dropMode = Objects.requireNonNull(dropMode, "dropMode cannot be null");

        if (startTime instanceof Lifetime && null == endTime) {
            this.lifetime = (Lifetime) startTime;
            this.useTemporalParameters = false;
        } else {
            this.useTemporalParameters = true;

            if (startTime instanceof Traversal) {
                this.startTimeTraversal = this.integrateChild(((Traversal<S, ?>) startTime).asAdmin());
            } else {
                this.startTime = startTime;
            }

            if (endTime instanceof Traversal) {
                this.endTimeTraversal = this.integrateChild(((Traversal<S, ?>) endTime).asAdmin());
            } else {
                this.endTime = endTime;
            }
        }

        this.propertyKey = propertyKey;
        this.propertyValue = propertyValue;
    }

    public LifetimeStep(final Traversal.Admin traversal, final Object startTime, final Object endTime,
            final String propertyKey, final String propertyValue, final LifetimeMode mode) {
        this(traversal, startTime, endTime, propertyKey, propertyValue, mode, DropMode.SOFT);
    }

    public LifetimeStep(final Traversal.Admin traversal, final Object startTime, final Object endTime,
            final String propertyKey, final String propertyValue) {
        this(traversal, startTime, endTime, propertyKey, propertyValue, LifetimeMode.REPLACE);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), this.lifetime, this.lifetimeTraversal, this.useTemporalParameters,
                this.startTime, this.endTime, this.mode, this.dropMode,
                this.startTimeTraversal, this.endTimeTraversal, this.propertyKey, this.propertyValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof LifetimeStep))
            return false;
        if (!super.equals(obj))
            return false;

        LifetimeStep<?> that = (LifetimeStep<?>) obj;

        if (!Objects.equals(lifetime, that.lifetime))
            return false;
        if (!Objects.equals(lifetimeTraversal, that.lifetimeTraversal))
            return false;
        if (useTemporalParameters != that.useTemporalParameters)
            return false;
        if (mode != that.mode || dropMode != that.dropMode)
            return false;
        if (!Objects.equals(startTime, that.startTime))
            return false;
        if (!Objects.equals(endTime, that.endTime))
            return false;
        if (!Objects.equals(startTimeTraversal, that.startTimeTraversal))
            return false;
        if (!Objects.equals(endTimeTraversal, that.endTimeTraversal))
            return false;
        if (propertyKey != null ? !propertyKey.equals(that.propertyKey) : that.propertyKey != null)
            return false;
        return propertyValue != null ? propertyValue.equals(that.propertyValue) : that.propertyValue == null;
    }

    @Override
    protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
        final Traverser.Admin<S> traverser = this.starts.next();

        // Evaluate traversal parameters at runtime
        final Lifetime actualLifetime = resolveLifetime(traverser);
        if (actualLifetime.isEmpty()) {
            if (mode == LifetimeMode.DROP) {
                return traverser;
            }
            throw new IllegalArgumentException("Cannot " + operationVerb() + " an empty lifetime");
        }
        final Element element = Lifetime.unwrapElement((Element) traverser.get());

        Lifetime finalLifetime = actualLifetime;
        if (mode == LifetimeMode.DROP || (mode == LifetimeMode.ADD && Lifetime.hasLifetimeProperties(element))) {
            final int count = actualLifetime.getIntervalCount();
            if (mode == LifetimeMode.ADD) {
                Lifetime currentLifetime = Lifetime.fromProperties(element);
                for (int i = 0; i < count; i++) {
                    currentLifetime = currentLifetime.addInterval(actualLifetime.getStartMillis(i), actualLifetime.getEndMillis(i));
                }
                finalLifetime = currentLifetime;
            } else if (mode == LifetimeMode.DROP) {
                final Lifetime initialLifetime = resolveCurrentLifetimeForDrop(element);
                Lifetime currentLifetime = initialLifetime;
                for (int i = 0; i < count; i++) {
                    currentLifetime = currentLifetime.dropInterval(actualLifetime.getStartMillis(i), actualLifetime.getEndMillis(i));
                }
                if (!Lifetime.hasLifetimeProperties(element) && currentLifetime.equals(initialLifetime)) {
                    return traverser;
                }
                finalLifetime = currentLifetime;
            }
        }

        if (mode == LifetimeMode.DROP && finalLifetime.isEmpty()) {
            if (dropMode == DropMode.HARD)
                element.remove();
            else if (dropMode == DropMode.SOFT)
                throw new IllegalArgumentException("Cannot soft drop an interval that leaves the lifetime empty");
            return traverser;
        }

        if (element instanceof Vertex) {
            final Vertex vertex = (Vertex) element;

            if (this.propertyKey != null && this.propertyValue != null) {
                validateVertexPropertyLifetime(vertex, finalLifetime);
                final List<VertexProperty<Object>> untimedProps = new ArrayList<>();
                vertex.properties(this.propertyKey).forEachRemaining(vp -> {
                    if (!Lifetime.hasLifetimeProperties(vp)) {
                        untimedProps.add(vp);
                    }
                });
                untimedProps.forEach(VertexProperty::remove);
                vertex.property(VertexProperty.Cardinality.list, this.propertyKey, this.propertyValue,
                        finalLifetime.toProperties());
            } else if (this.propertyKey != null) {
                validateVertexPropertyLifetime(vertex, finalLifetime);

                // Step 1: Store Previous metaProperties
                VertexProperty<Object> vp = vertex.property(propertyKey);
                Object propertyValue = vp.value();
                Map<String, Object> metaProperties = new HashMap<>();
                vp.properties().forEachRemaining(metaProp -> {
                    if (!isSystemLifetimeProperty(metaProp.key())) {
                        metaProperties.put(metaProp.key(), metaProp.value());
                    }
                });

                // Step 2: Delete the property
                vertex.property(this.propertyKey).remove();

                // Step 3: Recreate with extra meta-property
                metaProperties.putAll(finalLifetime.toPropertyMap());
                List<Object> args = new ArrayList<>();
                metaProperties.forEach((key, value) -> {
                    args.add(key);
                    args.add(value);
                });
                vertex.property(VertexProperty.Cardinality.single, this.propertyKey, propertyValue,
                        args.toArray(new Object[0]));
            } else {
                applyVertexLifetimeWithCascade(vertex, finalLifetime);
            }
        } else if (element instanceof Edge) {
            final Edge edge = (Edge) element;

            // For edges, validate that both vertices exist for the edge's full lifetime.
            if (validateEdgeLifetime(edge, finalLifetime)) {
                finalLifetime.attachTo(edge);
            } else {
                throw new IllegalArgumentException(
                        "Cannot " + operationVerb() + " edge lifetime [" + finalLifetime.getStartDate() + ", " + finalLifetime.getEndDate() +
                                "] because one or both vertices do not exist during this time period.");
            }
        } else if (element instanceof VertexProperty) {
            final VertexProperty<?> vp = (VertexProperty<?>) element;
            validateVertexPropertyLifetime(vp.element(), finalLifetime);
            finalLifetime.attachTo(element);
        }

        return traverser;
    }

    /**
     * Updates a vertex's lifetime and cascades the new bounds to timed incident edges and timed
     * vertex properties so they remain within a subspace of the vertex's lifetime.
     *
     * <p>All updates and removals are planned and validated before any graph mutation occurs so
     * {@link DropMode#SOFT} failures never leave the graph partially updated.</p>
     */
    private void applyVertexLifetimeWithCascade(final Vertex vertex, final Lifetime finalVertexLifetime) {
        final Set<Edge> incidentEdges = new LinkedHashSet<>();
        vertex.edges(Direction.BOTH).forEachRemaining(incidentEdges::add);

        final Map<Edge, Lifetime> edgesToUpdate = new LinkedHashMap<>();
        final List<Edge> edgesToRemove = new ArrayList<>();

        for (final Edge edge : incidentEdges) {
            final Vertex otherVertex = edge.outVertex().equals(vertex) ? edge.inVertex() : edge.outVertex();
            final Lifetime maxAllowedEdgeLifetime = (!otherVertex.equals(vertex) && Lifetime.hasLifetimeProperties(otherVertex))
                    ? finalVertexLifetime.intersection(Lifetime.fromProperties(otherVertex))
                    : finalVertexLifetime;

            if (!Lifetime.hasLifetimeProperties(edge)) {
                if (maxAllowedEdgeLifetime.isEmpty()) {
                    if (dropMode == DropMode.SOFT) {
                        throw new IllegalArgumentException(
                                "Cannot " + operationVerb() + " vertex lifetime [" + finalVertexLifetime.getStartDate() + ", " +
                                        finalVertexLifetime.getEndDate() + "] because incident edge " + edge.id() +
                                        " would have an empty lifetime.");
                    }
                    edgesToRemove.add(edge);
                }
                continue;
            }
            final Lifetime edgeLifetime = Lifetime.fromProperties(edge);
            final Lifetime updatedEdgeLifetime = edgeLifetime.intersection(maxAllowedEdgeLifetime);
            if (updatedEdgeLifetime.isEmpty()) {
                if (dropMode == DropMode.SOFT) {
                    throw new IllegalArgumentException(
                            "Cannot " + operationVerb() + " vertex lifetime [" + finalVertexLifetime.getStartDate() + ", " +
                                    finalVertexLifetime.getEndDate() + "] because incident edge " + edge.id() +
                                    " would have an empty lifetime.");
                }
                edgesToRemove.add(edge);
            } else if (!updatedEdgeLifetime.equals(edgeLifetime)) {
                edgesToUpdate.put(edge, updatedEdgeLifetime);
            }
        }

        final Map<VertexProperty<?>, Lifetime> propertiesToUpdate = new LinkedHashMap<>();
        final List<VertexProperty<?>> propertiesToRemove = new ArrayList<>();

        vertex.properties().forEachRemaining(vp -> {
            if (isSystemLifetimeProperty(vp.key()) || !Lifetime.hasLifetimeProperties(vp)) {
                return;
            }
            final Lifetime vpLifetime = Lifetime.fromProperties(vp);
            final Lifetime updatedVpLifetime = vpLifetime.intersection(finalVertexLifetime);
            if (updatedVpLifetime.isEmpty()) {
                if (dropMode == DropMode.SOFT) {
                    throw new IllegalArgumentException(
                            "Cannot " + operationVerb() + " vertex lifetime [" + finalVertexLifetime.getStartDate() + ", " +
                                    finalVertexLifetime.getEndDate() + "] because vertex property '" + vp.key() +
                                    "' would have an empty lifetime.");
                }
                propertiesToRemove.add(vp);
            } else if (!updatedVpLifetime.equals(vpLifetime)) {
                propertiesToUpdate.put(vp, updatedVpLifetime);
            }
        });

        edgesToUpdate.forEach((edge, lt) -> lt.attachTo(edge));
        edgesToRemove.forEach(Edge::remove);
        propertiesToUpdate.forEach((vp, lt) -> lt.attachTo(vp));
        propertiesToRemove.forEach(VertexProperty::remove);
        finalVertexLifetime.attachTo(vertex);
    }

    private Lifetime resolveCurrentLifetimeForDrop(final Element element) {
        if (Lifetime.hasLifetimeProperties(element)) {
            return Lifetime.fromProperties(element);
        }
        if (element instanceof Edge) {
            final Edge edge = (Edge) element;
            return Lifetime.fromProperties(edge.outVertex())
                    .intersection(Lifetime.fromProperties(edge.inVertex()));
        }
        if (element instanceof VertexProperty) {
            final VertexProperty<?> vp = (VertexProperty<?>) element;
            return Lifetime.fromProperties(vp.element());
        }
        return Lifetime.fromProperties(element);
    }

    private static boolean isSystemLifetimeProperty(final String key) {
        return Lifetime.START_TIME.equals(key)
                || Lifetime.END_TIME.equals(key)
                || Lifetime.TEMPORAL_INTERVALS.equals(key);
    }

    private String operationVerb() {
        if (mode == LifetimeMode.ADD) {
            return "add an interval to";
        }
        if (mode == LifetimeMode.DROP) {
            return "drop an interval from";
        }
        return "set";
    }

    private void validateVertexPropertyLifetime(final Vertex vertex, final Lifetime propertyLifetime) {
        if (!containsLifetime(vertex, propertyLifetime)) {
            throw new IllegalArgumentException(
                    "Cannot " + operationVerb() + " vertex property lifetime [" + propertyLifetime.getStartDate() + ", " +
                            propertyLifetime.getEndDate() + "] because the parent vertex does not exist during this time period.");
        }
    }

    private boolean validateEdgeLifetime(final Edge edge, final Lifetime edgeLifetime) {
        final Vertex inVertex = edge.inVertex();
        final Vertex outVertex = edge.outVertex();

        return containsLifetime(inVertex, edgeLifetime) &&
                containsLifetime(outVertex, edgeLifetime);
    }

    private boolean containsLifetime(final Vertex vertex, final Lifetime childLifetime) {
        if (!Lifetime.hasLifetimeProperties(vertex))
            return true;

        final Lifetime vertexLifetime = Lifetime.fromProperties(vertex);
        return vertexLifetime.contains(childLifetime);
    }

    private Lifetime resolveLifetime(final Traverser.Admin<S> traverser) {
        if (null != this.lifetime)
            return this.lifetime;

        if (this.useTemporalParameters)
            return resolveTemporalParameters(traverser);

        final Object value = TraversalUtil.apply(traverser, this.lifetimeTraversal);
        if (!(value instanceof Lifetime))
            throw new IllegalArgumentException("The lifetime traversal must produce a Lifetime, but produced " +
                    (value == null ? "null" : value.getClass().getName()));

        return (Lifetime) value;
    }

    private Lifetime resolveTemporalParameters(final Traverser.Admin<S> traverser) {
        final Object resolvedStartTime = null == this.startTimeTraversal ?
                this.startTime :
                TraversalUtil.apply(traverser, this.startTimeTraversal);
        final Object resolvedEndTime = null == this.endTimeTraversal ?
                this.endTime :
                TraversalUtil.apply(traverser, this.endTimeTraversal);

        if (resolvedStartTime instanceof Lifetime && null == resolvedEndTime)
            return (Lifetime) resolvedStartTime;

        return Lifetime.from(resolvedStartTime, resolvedEndTime);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S, E> List<Traversal.Admin<S, E>> getLocalChildren() {
        final List<Traversal.Admin<S, E>> children = new ArrayList<>();

        if (null != this.lifetimeTraversal)
            children.add((Traversal.Admin<S, E>) this.lifetimeTraversal);
        if (null != this.startTimeTraversal)
            children.add((Traversal.Admin<S, E>) this.startTimeTraversal);
        if (null != this.endTimeTraversal)
            children.add((Traversal.Admin<S, E>) this.endTimeTraversal);

        return children;
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return this.getSelfAndChildRequirements(TraverserRequirement.OBJECT);
    }

    @Override
    public void setTraversal(final Traversal.Admin<?, ?> parentTraversal) {
        super.setTraversal(parentTraversal);
        if (null != this.lifetimeTraversal)
            this.integrateChild(this.lifetimeTraversal);
        if (null != this.startTimeTraversal)
            this.integrateChild(this.startTimeTraversal);
        if (null != this.endTimeTraversal)
            this.integrateChild(this.endTimeTraversal);
    }

    @Override
    public LifetimeStep<S> clone() {
        final LifetimeStep<S> clone = (LifetimeStep<S>) super.clone();
        if (null != this.lifetimeTraversal)
            clone.lifetimeTraversal = this.lifetimeTraversal.clone();
        if (null != this.startTimeTraversal)
            clone.startTimeTraversal = this.startTimeTraversal.clone();
        if (null != this.endTimeTraversal)
            clone.endTimeTraversal = this.endTimeTraversal.clone();
        return clone;
    }

    public Lifetime getLifetime() {
        return this.lifetime;
    }

    public Traversal.Admin<S, ?> getLifetimeTraversal() {
        return this.lifetimeTraversal;
    }
}
