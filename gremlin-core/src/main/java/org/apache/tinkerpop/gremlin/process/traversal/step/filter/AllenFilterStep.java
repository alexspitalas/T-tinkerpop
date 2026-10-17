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
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;

import java.util.Objects;

/**
 * A filter step that implements all of Allen's temporal relationships.
 * This step evaluates temporal intervals between the current element and a reference element or {@link Lifetime}
 * by delegating to the highly optimized, multi-interval safe {@link Temporal} predicates.
 */
public final class AllenFilterStep<S, E> extends FilterStep<S> {

    private final Temporal relation;
    private final Object referenceTarget;

    public AllenFilterStep(final Traversal.Admin<S, E> traversal, 
                          final Temporal relation, 
                          final Object referenceTarget) {
        super(traversal);
        this.relation = relation;
        this.referenceTarget = referenceTarget;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final S currentObject = traverser.get();
        if (!(currentObject instanceof Element)) {
            return false;
        }
        
        // Delegate all multi-interval overlap sweep logic directly to the Temporal engine
        final Object target = referenceTarget instanceof Element
                ? Lifetime.unwrapElement((Element) referenceTarget)
                : referenceTarget;
        return relation.test(Lifetime.unwrapElement((Element) currentObject), target);
    }

    @Override
    public String toString() {
        return "AllenFilterStep(" + relation.name() + ", " + referenceTarget + ")";
    }

    @Override
    public AllenFilterStep<S, E> clone() {
        return (AllenFilterStep<S, E>) super.clone();
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Objects.hash(this.relation, this.referenceTarget);
    }

    @Override
    public boolean equals(final Object other) {
        if (!super.equals(other)) return false;
        final AllenFilterStep<?, ?> that = (AllenFilterStep<?, ?>) other;
        return Objects.equals(this.relation, that.relation)
                && Objects.equals(this.referenceTarget, that.referenceTarget);
    }

    public Temporal getRelation() {
        return relation;
    }

    public Element getReferenceElement() {
        return referenceTarget instanceof Element ? (Element) referenceTarget : null;
    }

    public Object getReferenceTarget() {
        return referenceTarget;
    }
}
