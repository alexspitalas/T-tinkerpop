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

/**
 * A filter step that implements all of Allen's temporal relationships.
 * This step evaluates temporal intervals between the current element and a reference element
 * by delegating to the highly optimized, multi-interval safe {@link Temporal} predicates.
 */
public final class AllenFilterStep<S, E> extends FilterStep<S> {

    private final Temporal relation;
    private final Element referenceElement;

    public AllenFilterStep(final Traversal.Admin<S, E> traversal, 
                          final Temporal relation, 
                          final Element referenceElement) {
        super(traversal);
        this.relation = relation;
        this.referenceElement = referenceElement;
    }

    @Override
    protected boolean filter(final Traverser.Admin<S> traverser) {
        final S currentObject = traverser.get();
        if (!(currentObject instanceof Element)) {
            return false;
        }
        
        // Delegate all multi-interval overlap sweep logic directly to the Temporal engine
        return relation.test(currentObject, referenceElement);
    }

    @Override
    public String toString() {
        return "AllenFilterStep(" + relation.name() + ", " + referenceElement + ")";
    }

    @Override
    public AllenFilterStep<S, E> clone() {
        return (AllenFilterStep<S, E>) super.clone();
    }

    public Temporal getRelation() {
        return relation;
    }

    public Element getReferenceElement() {
        return referenceElement;
    }
}
