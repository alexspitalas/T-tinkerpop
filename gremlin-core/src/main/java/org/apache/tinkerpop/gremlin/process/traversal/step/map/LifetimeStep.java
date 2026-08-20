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

import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import java.util.ArrayList;
import java.util.HashMap;
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
            final String propertyKey, final String propertyValue, final LifetimeMode mode) {
        super(traversal);
        this.mode = mode != null ? mode : LifetimeMode.REPLACE;

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

        this.propertyKey = propertyKey;
        this.propertyValue = propertyValue;
    }

    public LifetimeStep(final Traversal.Admin traversal, final Object startTime, final Object endTime,
            final String propertyKey, final String propertyValue) {
        this(traversal, startTime, endTime, propertyKey, propertyValue, LifetimeMode.REPLACE);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), this.lifetime, this.lifetimeTraversal, this.useTemporalParameters,
                this.startTime, this.endTime,
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
        final Element element = (Element) traverser.get();

        Lifetime finalLifetime = actualLifetime;
        if (mode != LifetimeMode.REPLACE && Lifetime.hasLifetimeProperties(element)) {
            Lifetime currentLifetime = Lifetime.fromProperties(element);
            if (mode == LifetimeMode.ADD) {
                finalLifetime = currentLifetime.addInterval(actualLifetime.getStartDate(), actualLifetime.getEndDate());
            } else if (mode == LifetimeMode.DROP) {
                finalLifetime = currentLifetime.dropInterval(actualLifetime.getStartDate(), actualLifetime.getEndDate());
            }
        }

        if (element instanceof Vertex) {
            final Vertex vertex = (Vertex) element;

            if (this.propertyKey != null && this.propertyValue != null) {
                vertex.property(VertexProperty.Cardinality.single, this.propertyKey, this.propertyValue,
                        finalLifetime.toProperties());
            } else if (this.propertyKey != null) {

                // Step 1: Store Previous metaProperties
                VertexProperty<Object> vp = vertex.property(propertyKey);
                Object propertyValue = vp.value();
                Map<String, Object> metaProperties = new HashMap<>();
                vp.properties().forEachRemaining(metaProp -> metaProperties.put(metaProp.key(), metaProp.value()));

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
                finalLifetime.attachTo(vertex);
            }
        } else if (element instanceof Edge) {
            final Edge edge = (Edge) element;

            // For edges, validate that both vertices exist for the edge's full lifetime.
            if (validateEdgeLifetime(edge, finalLifetime)) {
                finalLifetime.attachTo(edge);
            } else {
                // If validation fails, throw an error
                throw new IllegalArgumentException(
                        "Cannot update edge with lifetime [" + finalLifetime.getStartDate() + ", " + finalLifetime.getEndDate() +
                                "] because one or both vertices do not exist during this time period.");
            }
        }

        return traverser;
    }

    private boolean validateEdgeLifetime(Edge edge, Lifetime edgeLifetime) {
        Vertex inVertex = edge.inVertex();
        Vertex outVertex = edge.outVertex();

        return containsEdgeLifetime(inVertex, edgeLifetime) &&
                containsEdgeLifetime(outVertex, edgeLifetime);
    }

    private boolean containsEdgeLifetime(final Vertex vertex, final Lifetime edgeLifetime) {
        if (!Lifetime.hasLifetimeProperties(vertex))
            return true;

        final Lifetime vertexLifetime = Lifetime.fromProperties(vertex);
        return vertexLifetime.contains(edgeLifetime);
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
