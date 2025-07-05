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
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

/**
 * A step that extracts the endTime property value from an element.
 * Works with vertices, edges, and vertex properties.
 * Returns null for elements that don't have endTime properties.
 */
public class GetEndTimeStep<S extends Element> extends ScalarMapStep<S, String> {

    public GetEndTimeStep(final Traversal.Admin traversal) {
        super(traversal);
    }

    @Override
    protected String map(final Traverser.Admin<S> traverser) {
        final S element = traverser.get();
        
        String endTime = null;
        
        if (element instanceof Vertex) {
            VertexProperty<Object> property = ((Vertex) element).property("endTime");
            if (property.isPresent()) {
                endTime = String.valueOf(property.value());
            }
        } else if (element instanceof Edge) {
            org.apache.tinkerpop.gremlin.structure.Property<Object> property = ((Edge) element).property("endTime");
            if (property.isPresent()) {
                endTime = String.valueOf(property.value());
            }
        } else if (element instanceof VertexProperty) {
            org.apache.tinkerpop.gremlin.structure.Property<Object> property = ((VertexProperty) element).property("endTime");
            if (property.isPresent()) {
                endTime = String.valueOf(property.value());
            }
        } else {
            throw new IllegalStateException("GetEndTimeStep does not support element type: " + element.getClass().getSimpleName());
        }
        
        return endTime;
    }
} 