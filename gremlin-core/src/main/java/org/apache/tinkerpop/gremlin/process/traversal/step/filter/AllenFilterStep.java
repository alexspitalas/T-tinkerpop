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
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.Temporal;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.temporal.TemporalElement;

import java.util.Objects;

/**
 * A filter step that implements Allen's temporal relationships (plus {@code intersects} and {@code disjoint})
 * across single intervals ({@link Lifetime.Interval}), multi-interval lifetimes ({@link Lifetime}),
 * graph {@link Element}s, and point instants. Supports holistic ({@link Temporal.Quantifier#ALL}, default)
 * and existential sub-interval ({@link Temporal.Quantifier#ANY}) quantification via {@code .with(Temporal.ANY)}.
 */
public final class AllenFilterStep<S, E> extends FilterStep<S> implements Configuring {

    private final Temporal relation;
    private final Object referenceTarget;
    private Temporal.Quantifier quantifier;
    private Parameters parameters = new Parameters();
    private Lifetime cachedTargetLifetime = null;

    public AllenFilterStep(final Traversal.Admin<S, E> traversal, 
                          final Temporal relation, 
                          final Object referenceTarget) {
        this(traversal, relation, referenceTarget, Temporal.Quantifier.ALL);
    }

    public AllenFilterStep(final Traversal.Admin<S, E> traversal,
                          final Temporal relation,
                          final Object referenceTarget,
                          final Temporal.Quantifier quantifier) {
        super(traversal);
        this.relation = Objects.requireNonNull(relation, "relation cannot be null");
        this.referenceTarget = referenceTarget;
        this.quantifier = Objects.requireNonNull(quantifier, "quantifier cannot be null");
        if (referenceTarget != null && !(referenceTarget instanceof Element)) {
            this.cachedTargetLifetime = Temporal.extractLifetime(referenceTarget);
        }
    }

    @Override
    public void configure(final Object... keyValues) {
        if (keyValues.length == 2) {
            final Object key = keyValues[0];
            final Object val = keyValues[1];
            if (Temporal.ANY.equals(key) && Boolean.TRUE.equals(val)) {
                this.quantifier = Temporal.Quantifier.ANY;
                return;
            }
            if (Temporal.ALL.equals(key) && Boolean.TRUE.equals(val)) {
                this.quantifier = Temporal.Quantifier.ALL;
                return;
            }
            if (Temporal.quantifier.equals(key) || "quantifier".equals(key)) {
                if (val instanceof Temporal.Quantifier) {
                    this.quantifier = (Temporal.Quantifier) val;
                    return;
                }
                if (Temporal.ANY.equalsIgnoreCase(String.valueOf(val))) {
                    this.quantifier = Temporal.Quantifier.ANY;
                    return;
                }
                if (Temporal.ALL.equalsIgnoreCase(String.valueOf(val))) {
                    this.quantifier = Temporal.Quantifier.ALL;
                    return;
                }
            }
        }
        this.parameters.set(null, keyValues);
    }

    @Override
    public Parameters getParameters() {
        return this.parameters;
    }

    private Lifetime getTargetLifetime() {
        if (referenceTarget instanceof Element) {
            return Lifetime.getLifetimeFromProperties((Element) referenceTarget);
        }
        if (cachedTargetLifetime == null && referenceTarget != null) {
            this.cachedTargetLifetime = Temporal.extractLifetime(referenceTarget);
        }
        return cachedTargetLifetime;
    }

    public boolean filterElement(final Object element) {
        if (element == null) {
            return false;
        }
        final Lifetime targetLt = getTargetLifetime();
        if (targetLt == null || targetLt.isEmpty()) {
            return false;
        }

        final Lifetime sourceLt = element instanceof Element
                ? Lifetime.getLifetimeFromProperties((Element) element)
                : Temporal.extractLifetime(element);
        if (sourceLt == null || sourceLt.isEmpty()) {
            return false;
        }

        return relation.test(sourceLt, targetLt, quantifier);
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        return filterElement(traverser.get());
    }

    @Override
    public String toString() {
        return quantifier == Temporal.Quantifier.ANY
                ? "AllenFilterStep(" + relation.name() + ", " + referenceTarget + ", ANY)"
                : "AllenFilterStep(" + relation.name() + ", " + referenceTarget + ")";
    }

    @Override
    public AllenFilterStep<S, E> clone() {
        final AllenFilterStep<S, E> clone = (AllenFilterStep<S, E>) super.clone();
        clone.quantifier = this.quantifier;
        clone.parameters = this.parameters.clone();
        clone.cachedTargetLifetime = this.cachedTargetLifetime;
        return clone;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Objects.hash(this.relation, this.referenceTarget, this.quantifier);
    }

    @Override
    public boolean equals(final Object other) {
        if (!super.equals(other)) return false;
        final AllenFilterStep<?, ?> that = (AllenFilterStep<?, ?>) other;
        return Objects.equals(this.relation, that.relation)
                && Objects.equals(this.referenceTarget, that.referenceTarget)
                && this.quantifier == that.quantifier;
    }

    public Temporal getRelation() {
        return relation;
    }

    public Temporal.Quantifier getQuantifier() {
        return quantifier;
    }

    public Element getReferenceElement() {
        return referenceTarget instanceof Element ? (Element) referenceTarget : null;
    }

    public Object getReferenceTarget() {
        return referenceTarget;
    }
}
