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
import org.apache.tinkerpop.gremlin.structure.Vertex;

import java.util.NoSuchElementException;

public class LifetimeStep<S> extends AbstractStep<S, S> implements  TraversalParent {
    private final String startTime;
    private final String endTime;

    public LifetimeStep(final Traversal.Admin traversal, final String startTime, final String endTime) {
        super(traversal);
        this.startTime = startTime;
        this.endTime = endTime;
    }

    @Override
    public int hashCode() { return super.hashCode() ^ this.startTime.hashCode() ^ this.endTime.hashCode() ; }

    @Override
    protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
        final Traverser.Admin<S> traverser = this.starts.next();
        
        if( traverser.get() instanceof Vertex){
            final Vertex vertex = (Vertex) traverser.get();
            vertex.property("startTime", this.startTime);
            vertex.property("endTime", this.endTime);
        }
        return traverser;
    }
}