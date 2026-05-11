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
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GetStartTimeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GetEndTimeStep;
import org.apache.tinkerpop.gremlin.structure.Element;

import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;        
import org.apache.tinkerpop.gremlin.structure.Property;        
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.util.LifetimeHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Date;

public class LifetimeStep<S> extends AbstractStep<S, S> implements  TraversalParent {
    private Object startTime; // Can be date-like Object or Traversal
    private Object endTime;   // Can be date-like Object or Traversal
    private final String propertyKey;
    private final String propertyValue;
    public static final String DEFAULT_ENDTIME = LifetimeHelper.DEFAULT_ENDTIME;

    public LifetimeStep(final Traversal.Admin traversal, final Object startTime, final Object endTime, final String propertyKey, final String propertyValue) {
        super(traversal);
        
        // Validate startTime is not null
        if (startTime == null) {
            throw new IllegalArgumentException("Start time cannot be null");
        }
        
        this.startTime = startTime;
        this.endTime = (endTime == null) ? DEFAULT_ENDTIME : endTime;
        this.propertyKey = propertyKey;
        this.propertyValue = propertyValue;
        
        // Traversal-backed parameters are validated when they are evaluated for a traverser.
        if (!(startTime instanceof Traversal) && !(this.endTime instanceof Traversal)) {
            validateTimeParameters(startTime, this.endTime);
        }
    }

    private void validateTimeParameters(final Object startTime, final Object endTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("Start time cannot be null or empty");
        }
        
        final Date startDate = LifetimeHelper.toStartDate(startTime);
        final Date endDate = LifetimeHelper.toEndDate(endTime);
        validateTimeParameters(startTime, endTime, startDate, endDate);
    }

    private void validateTimeParameters(final Object startTime, final Object endTime, final Date startDate, final Date endDate) {
        
        if (!startDate.before(endDate)) {
            throw new IllegalArgumentException("Start time (" + startTime + ") must be before end time (" + endTime + ")");
        }
    }
    
    @Override
    public int hashCode() {
        if (this.propertyKey == null) {
            return super.hashCode() ^ this.startTime.hashCode() ^ this.endTime.hashCode();
        }
        return super.hashCode() ^ this.startTime.hashCode() ^ this.endTime.hashCode() ^ this.propertyKey.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LifetimeStep)) return false;
        if (!super.equals(obj)) return false;
        
        LifetimeStep<?> that = (LifetimeStep<?>) obj;
        
        if (!startTime.equals(that.startTime)) return false;
        if (!endTime.equals(that.endTime)) return false;
        if (propertyKey != null ? !propertyKey.equals(that.propertyKey) : that.propertyKey != null) return false;
        return propertyValue != null ? propertyValue.equals(that.propertyValue) : that.propertyValue == null;
    }

    @Override
    protected Traverser.Admin<S> processNextStart() throws NoSuchElementException {
      final Traverser.Admin<S> traverser = this.starts.next();
        
      // Evaluate traversal parameters at runtime
      Object actualStartTime = evaluateTimeParameter(this.startTime, traverser);
      Object actualEndTime = evaluateTimeParameter(this.endTime, traverser);
      final Date actualStartDate = LifetimeHelper.toStartDate(actualStartTime);
      final Date actualEndDate = LifetimeHelper.toEndDate(actualEndTime);
      validateTimeParameters(actualStartTime, actualEndTime, actualStartDate, actualEndDate);
        
      if( traverser.get() instanceof Vertex){
        final Vertex vertex = (Vertex) traverser.get();

        if (this.propertyKey != null && this.propertyValue != null){
            vertex.property(VertexProperty.Cardinality.single, this.propertyKey, this.propertyValue, "startTime", actualStartTime , "endTime", actualEndTime);
        }else if (this.propertyKey != null){
            
          // Step 1: Store Previous metaProperties 
          VertexProperty<Object> vp = vertex.property(propertyKey); 
          Object propertyValue = vp.value();
          Map<String, Object> metaProperties = new HashMap<>();
          vp.properties().forEachRemaining(metaProp -> metaProperties.put(metaProp.key(), metaProp.value()));

          // Step 2: Delete the property
          vertex.property(this.propertyKey).remove();

          // Step 3: Recreate with extra meta-property
          metaProperties.put("startTime", actualStartTime);
          metaProperties.put("endTime", actualEndTime); // Add new meta-property
          List<Object> args = new ArrayList<>();
          metaProperties.forEach((key, value) -> {
              args.add(key);
              args.add(value);
          });
          vertex.property(VertexProperty.Cardinality.single, this.propertyKey, propertyValue, args.toArray(new Object[0]));
          }else{
            vertex.property("startTime", actualStartTime);
            vertex.property("endTime", actualEndTime);
          }
      } else if (traverser.get() instanceof Edge) {
          final Edge edge = (Edge) traverser.get();
          
          // For edges, validate that both vertices exist during the edge's lifetime
          if (validateEdgeLifetime(edge, actualStartDate, actualEndDate)) {
              edge.property("startTime", actualStartDate);
              edge.property("endTime", actualEndDate);
          } else {
              // If validation fails, throw an error
              throw new IllegalArgumentException("Cannot create edge with lifetime [" + actualStartTime + ", " + actualEndTime + 
                  "] because one or both vertices do not exist during this time period.");
          }
      }

      return traverser;
    }
    

    private boolean validateEdgeLifetime(Edge edge, Date edgeStartTime, Date edgeEndTime) {
        Vertex inVertex = edge.inVertex();
        Vertex outVertex = edge.outVertex();
        
        // Check if both vertices have lifetime properties
        if (!hasLifetimeProperty(inVertex) || !hasLifetimeProperty(outVertex)) {
            // If vertices don't have lifetime properties, assume they exist for all time
            return true;
        }
        
        // Get vertex lifetimes
        Date inVertexStartTime = LifetimeHelper.toStartDate(getVertexStartTime(inVertex));
        Date inVertexEndTime = LifetimeHelper.toEndDate(getVertexEndTime(inVertex));
        Date outVertexStartTime = LifetimeHelper.toStartDate(getVertexStartTime(outVertex));
        Date outVertexEndTime = LifetimeHelper.toEndDate(getVertexEndTime(outVertex));
        
        // Check if edge lifetime overlaps with both vertex lifetimes
        return timeRangesOverlap(edgeStartTime, edgeEndTime, inVertexStartTime, inVertexEndTime) &&
               timeRangesOverlap(edgeStartTime, edgeEndTime, outVertexStartTime, outVertexEndTime);
    }
    

    private boolean hasLifetimeProperty(Vertex vertex) {
        return vertex.property("startTime").isPresent() && vertex.property("endTime").isPresent();
    }
    
    private Object getVertexStartTime(Vertex vertex) {
        Property<Object> prop = vertex.property("startTime");
        return prop.isPresent() ? prop.value() : null;
    }
    
    private Object getVertexEndTime(Vertex vertex) {
        Property<Object> prop = vertex.property("endTime");
        return prop.isPresent() ? prop.value() : null;
    }
    
    private boolean timeRangesOverlap(Date start1, Date end1, Date start2, Date end2) {
        // Check if the ranges overlap: start1 <= end2 AND start2 <= end1
        return !start1.after(end2) && !start2.after(end1);
    }

    private Object evaluateTimeParameter(Object timeParam, Traverser.Admin<S> traverser) {
        if (timeParam instanceof Traversal) {
            @SuppressWarnings("unchecked")
            Traversal<?, Object> traversal = (Traversal<?, Object>) timeParam;
            
            Traversal.Admin<?, Object> adminTraversal = traversal.asAdmin();
            
            if (adminTraversal.getSteps().size() > 0) {
                Object step = adminTraversal.getSteps().get(0);
                if (step instanceof GetStartTimeStep) {
                    if (traverser.get() instanceof Element) {
                        Element element = (Element) traverser.get();
                        Property<Object> prop = element.property("startTime");
                        if (prop.isPresent()) {
                            return prop.value();
                        } else {
                            throw new IllegalArgumentException("Cannot use getStartTime() when the element does not have a startTime property. Please provide an explicit startTime value.");
                        }
                    }
                } else if (step instanceof GetEndTimeStep) {
                    if (traverser.get() instanceof Element) {
                        Element element = (Element) traverser.get();
                        Property<Object> prop = element.property("endTime");
                        if (prop.isPresent()) {
                            return prop.value();
                        } else {
                            throw new IllegalArgumentException("Cannot use getEndTime() when the element does not have an endTime property. Please provide an explicit endTime value.");
                        }
                    }
                }
            }
            
            // For other traversals, throw an error
            throw new IllegalArgumentException("Unsupported traversal type for time parameter. Only getStartTime() and getEndTime() are supported.");
        }
        return timeParam;
    }
    
    public Object getStartTime() {
        return startTime;
    }

    public Object getEndTime() {
        return endTime;
    }
} 
