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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Arrays;

public class LifetimeStep<S> extends AbstractStep<S, S> implements  TraversalParent {
    private Object startTime; // Can be String or Traversal
    private Object endTime;   // Can be String or Traversal
    private final String propertyKey;
    private final String propertyValue;
    public static final String DEFAULT_ENDTIME = "1e10";
    
    // Common date formats to try
    private static final String[] DATE_FORMATS = {
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy",
        "MM/dd/yyyy HH:mm:ss",
        "MM/dd/yyyy",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    };

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
        
        // Validate the time parameters only if they are strings
        if (startTime instanceof String && this.endTime instanceof String) {
            validateTimeParameters((String) startTime, (String) this.endTime);
        }
    }

    private void validateTimeParameters(String startTime, String endTime) {
        if (startTime == null || startTime.trim().isEmpty()) {
            throw new IllegalArgumentException("Start time cannot be null or empty");
        }
        
        if (endTime == null || endTime.trim().isEmpty()) {
            throw new IllegalArgumentException("End time cannot be null or empty");
        }
        
        Date startDate = parseDate(startTime);
        Date endDate = parseDate(endTime);
        
        if (startDate == null) {
            throw new IllegalArgumentException("Start time '" + startTime + "' is not in a valid date format. Supported formats: " + Arrays.toString(DATE_FORMATS));
        }
        
        if (endDate == null) {
            throw new IllegalArgumentException("End time '" + endTime + "' is not in a valid date format. Supported formats: " + Arrays.toString(DATE_FORMATS));
        }
        
        if (!startDate.before(endDate)) {
            throw new IllegalArgumentException("Start time (" + startTime + ") must be before end time (" + endTime + ")");
        }
    }
    
    private Date parseDate(String dateString) {
        if (DEFAULT_ENDTIME.equals(dateString)) {
            return new Date(Long.MAX_VALUE);
        }
        
        try {
            long timestamp = Long.parseLong(dateString);
            return new Date(timestamp);
        } catch (NumberFormatException e) {
        }
        
        // Try each date format
        for (String format : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format);
                sdf.setLenient(false); // Strict parsing
                Date parsedDate = sdf.parse(dateString);
                
                // Additional validation: check if the parsed date matches the original string
                // This prevents cases like "2023-01-01T25:00:00" from being parsed as valid
                String formattedBack = sdf.format(parsedDate);
                if (!dateString.equals(formattedBack)) {
                    continue; // Try next format
                }
                
                return parsedDate;
            } catch (ParseException e) {
            }
        }
        
        return null; 
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
      String actualStartTime = evaluateTimeParameter(this.startTime, traverser);
      String actualEndTime = evaluateTimeParameter(this.endTime, traverser);
        
      if( traverser.get() instanceof Vertex){
        final Vertex vertex = (Vertex) traverser.get();

        if (this.propertyKey != null && this.propertyValue != null){
            vertex.property(VertexProperty.Cardinality.single, this.propertyKey, this.propertyValue, "startTime", actualStartTime , "endTime", actualEndTime);
        }else if (this.propertyKey != null){
            
          // Step 1: Store Previous metaProperties 
          VertexProperty<Object> vp = vertex.property(propertyKey); 
          String propertyValue = (String) vp.value(); 
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
      }else if ( traverser.get() instanceof  Edge){
          final Edge edge = (Edge) traverser.get();
          edge.property("startTime", actualStartTime);
          edge.property("endTime", actualEndTime);
      }

      return traverser;
    }
    
    private String evaluateTimeParameter(Object timeParam, Traverser.Admin<S> traverser) {
        if (timeParam instanceof String) {
            return (String) timeParam;
        } else if (timeParam instanceof Traversal) {
            @SuppressWarnings("unchecked")
            Traversal<?, String> traversal = (Traversal<?, String>) timeParam;
            
            Traversal.Admin<?, String> adminTraversal = traversal.asAdmin();
            
            if (adminTraversal.getSteps().size() > 0) {
                Object step = adminTraversal.getSteps().get(0);
                if (step instanceof GetStartTimeStep) {
                    if (traverser.get() instanceof Element) {
                        Element element = (Element) traverser.get();
                        Property<String> prop = element.property("startTime");
                        if (prop.isPresent()) {
                            return prop.value();
                        } else {
                            throw new IllegalArgumentException("Cannot use getStartTime() when the element does not have a startTime property. Please provide an explicit startTime value.");
                        }
                    }
                } else if (step instanceof GetEndTimeStep) {
                    if (traverser.get() instanceof Element) {
                        Element element = (Element) traverser.get();
                        Property<String> prop = element.property("endTime");
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
        return DEFAULT_ENDTIME;
    }
    

    


    public Object getStartTime() {
        return startTime;
    }

    public Object getEndTime() {
        return endTime;
    }
} 
