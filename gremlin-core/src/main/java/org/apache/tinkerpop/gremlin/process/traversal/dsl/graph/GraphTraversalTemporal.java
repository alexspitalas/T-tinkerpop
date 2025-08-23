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
package org.apache.tinkerpop.gremlin.process.traversal.dsl.graph;

import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TemporalEqualsStep;
import org.apache.tinkerpop.gremlin.structure.Element;

/**
 * Extension to GraphTraversal for temporal functionality.
 * This mixin interface adds temporal relationship methods to GraphTraversal.
 */
public interface GraphTraversalTemporal<S, E> {

    /**
     * Get the admin traversal for adding steps
     */
    GraphTraversal.Admin<S, E> asAdmin();

    /**
     * Filters elements that are temporally EQUAL to the reference element.
     * Element X equals Y if X and Y have identical start and end times.
     * 
     * This implements Allen's temporal "equals" relationship.
     * 
     * @param referenceElement the element to compare temporal intervals with
     * @return the traversal with temporal equals filtering applied
     * @since 4.0.0-temporal
     */
    default GraphTraversal<S,S> temporalEquals(final Element referenceElement) {
        this.asAdmin().getBytecode().addStep("temporalEquals", referenceElement);
        return this.asAdmin().addStep(new TemporalEqualsStep<>(this.asAdmin(), referenceElement));
    }

}