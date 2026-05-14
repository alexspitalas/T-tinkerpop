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
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;

import java.util.Date;

/**
 * A step that extracts the startTime property value from an element.
 * Works with vertices, edges, and vertex properties.
 * Returns null for elements that don't have startTime properties.
 */
public class GetStartTimeStep<S extends Element> extends ScalarMapStep<S, Date> {

    public GetStartTimeStep(final Traversal.Admin traversal) {
        super(traversal);
    }

    @Override
    protected Date map(final Traverser.Admin<S> traverser) {
        final S element = traverser.get();
        return LifetimeHelper.getStartDateProperty(element);
    }
} 
