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
import org.apache.tinkerpop.gremlin.structure.VertexProperty;        
import org.apache.tinkerpop.gremlin.structure.Edge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.NoSuchElementException;

public class LifetimeStep<S> extends AbstractStep<S, S> implements  TraversalParent {
    private final String startTime;
    private final String endTime;
    private final String propertyKey;
    private final String propertyValue;
    public static final String DEFAULT_ENDTIME = "1e10";

    public LifetimeStep(final Traversal.Admin traversal, final String startTime, final String endTime, final String propertyKey, final String propertyValue) {
        super(traversal);
        this.startTime = startTime;
        this.endTime = (endTime == null) ? DEFAULT_ENDTIME : endTime;
        this.propertyKey = propertyKey;
        this.propertyValue = propertyValue;
    }

    @Override
    public int hashCode() {
        if (this.propertyKey == null) {
            return super.hashCode() ^ this.startTime.hashCode() ^ this.endTime.hashCode();
        }
        return super.hashCode() ^ this.startTime.hashCode() ^ this.endTime.hashCode() ^ this.propertyKey.hashCode();
    }

    @Override
    protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
      final Traverser.Admin<S> traverser = this.starts.next();
        
      if( traverser.get() instanceof Vertex){
        final Vertex vertex = (Vertex) traverser.get();

        if (this.propertyKey != null && this.propertyValue != null){
            vertex.property(VertexProperty.Cardinality.single, this.propertyKey, this.propertyValue, "startTime", this.startTime , "endTime", this.endTime);
        }else if (this.propertyKey != null){
            
          // Step 1: Store Previous metaProperties 
          VertexProperty<Object> vp = vertex.property(propertyKey); 
          String propertyValue = (String) vp.value(); 
          Map<String, Object> metaProperties = new HashMap<>();
          vp.properties().forEachRemaining(metaProp -> metaProperties.put(metaProp.key(), metaProp.value()));

          // Step 2: Delete the property
          vertex.property(this.propertyKey).remove();

          // Step 3: Recreate with extra meta-property
          metaProperties.put("startTime", this.startTime);
          metaProperties.put("endTime", this.endTime); // Add new meta-property
          List<Object> args = new ArrayList<>();
          metaProperties.forEach((key, value) -> {
              args.add(key);
              args.add(value);
          });
          vertex.property(VertexProperty.Cardinality.single, this.propertyKey, propertyValue, args.toArray(new Object[0]));
          }else{
            vertex.property("startTime", this.startTime);
            vertex.property("endTime", this.endTime);
          }
      }else if ( traverser.get() instanceof  Edge){
            final Edge edge = (Edge) traverser.get();
            edge.property("startTime", this.startTime);
            edge.property("endTime", this.endTime);
      }

      return traverser;
    }
} 
