/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.step.filter;

import org.apache.tinkerpop.gremlin.AbstractGremlinTest;
import org.apache.tinkerpop.gremlin.FeatureRequirement;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.AbstractGremlinProcessTest;
import org.apache.tinkerpop.gremlin.process.GremlinProcessRunner;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalEngine;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AllenOperators;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

import static org.apache.tinkerpop.gremlin.LoadGraphWith.GraphData.MODERN;
import static org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__.*;
import static org.junit.Assert.*;

/**
 * Test cases for Allen temporal operators
 * 
 * @author TinkerPop Temporal Extension
 */
@RunWith(GremlinProcessRunner.class)
public abstract class AllenFilterStepTest extends AbstractGremlinProcessTest {

    // Abstract methods for test traversals
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalBefore_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalAfter_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalOverlaps_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalDuring_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalContains_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalEquals_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalMeets_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalStartedBy_refElement();
    public abstract Traversal<Vertex, Vertex> get_g_V_temporalFinishes_refElement();
    
    public abstract Traversal<Vertex, Vertex> get_g_V_allen_before_traversal();
    public abstract Traversal<Edge, Edge> get_g_E_temporalOverlaps_vertex();
    public abstract Traversal<Vertex, Vertex> get_g_V_allen_generic_before();

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_PROPERTY)
    public void g_V_temporalBefore_refElement() {
        // Create test temporal graph
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_temporalBefore_refElement();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        assertFalse(traversal.hasNext());
        
        // Verify temporal relationships - should find events before "meeting_execution"
        assertTrue("Should find vertices temporally before reference", results.size() > 0);
        
        // Validate each result actually has the correct temporal relationship
        for (Vertex v : results) {
            assertTrue("Vertex should have temporal properties", 
                v.property("startTime").isPresent() && v.property("endTime").isPresent());
            
            // Verify this vertex is actually before the reference
            String eventType = v.value("event");
            assertTrue("Should be events that happen before meeting execution", 
                eventType.equals("meeting_planning") || eventType.equals("preparation"));
        }
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_PROPERTY)
    public void g_V_temporalOverlaps_refElement() {
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_temporalOverlaps_refElement();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        
        // Test overlap logic - should find "followup" event that overlaps with "meeting_planning"
        assertTrue("Should find overlapping events", results.size() > 0);
        
        for (Vertex v : results) {
            AllenOperators.TemporalInterval interval = AllenOperators.getTemporalInterval(v);
            assertNotNull("Vertex should have valid temporal interval", interval);
            
            String eventType = v.value("event");
            assertEquals("Should find the followup event that overlaps", "followup", eventType);
        }
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_PROPERTY)
    public void g_V_temporalDuring_refElement() {
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_temporalDuring_refElement();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        
        // Verify "during" relationship - should find "preparation" during "meeting_planning"
        assertTrue("Should find events during reference period", results.size() > 0);
        
        for (Vertex v : results) {
            assertTrue("Vertex should be during reference element", 
                v.property("startTime").isPresent() && v.property("endTime").isPresent());
            
            String eventType = v.value("event");
            assertEquals("Should find preparation event during meeting planning", "preparation", eventType);
        }
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    public void g_V_temporalContains_refElement() {
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_temporalContains_refElement();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        
        // Should find "meeting_planning" that contains "preparation"
        assertTrue("Should find containing events", results.size() > 0);
        
        for (Vertex v : results) {
            String eventType = v.value("event");
            assertEquals("Should find meeting_planning that contains preparation", "meeting_planning", eventType);
        }
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    public void g_V_temporalMeets_refElement() {
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_temporalMeets_refElement();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        
        // Should find events that meet "meeting_execution" (start when it starts)
        // In our test data, "transition" meets both planning and execution
        for (Vertex v : results) {
            String eventType = v.value("event");
            assertEquals("Should find transition event that meets execution", "transition", eventType);
        }
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    @FeatureRequirement(featureClass = Graph.Features.EdgeFeatures.class, feature = Graph.Features.EdgeFeatures.FEATURE_ADD_EDGES)
    public void g_E_temporalOverlaps_vertex() {
        setupTemporalTestGraph();
        
        final Traversal<Edge, Edge> traversal = get_g_E_temporalOverlaps_vertex();
        printTraversalForm(traversal);
        
        List<Edge> results = traversal.toList();
        
        // Test that edges can also use Allen operators
        for (Edge e : results) {
            assertTrue("Edge should have temporal properties", 
                e.property("startTime").isPresent() && e.property("endTime").isPresent());
        }
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    public void g_V_allen_before_traversal() {
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_allen_before_traversal();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        
        // Test using traversal as reference instead of direct element
        assertNotNull("Should handle traversal-based Allen operations", results);
        assertTrue("Should find events before meeting execution via traversal", results.size() > 0);
    }

    @Test
    @FeatureRequirement(featureClass = Graph.Features.VertexFeatures.class, feature = Graph.Features.VertexFeatures.FEATURE_ADD_VERTICES)
    public void g_V_allen_generic_before() {
        setupTemporalTestGraph();
        
        final Traversal<Vertex, Vertex> traversal = get_g_V_allen_generic_before();
        printTraversalForm(traversal);
        
        List<Vertex> results = traversal.toList();
        
        // Test generic allen() method with BEFORE relation
        assertTrue("Generic allen() method should work", results.size() > 0);
    }

    @Test 
    public void testAllenOperatorUtilities() {
        // Test the utility functions directly
        AllenOperators.TemporalInterval interval1 = new AllenOperators.TemporalInterval(
            new java.util.Date(1000), new java.util.Date(2000));
        AllenOperators.TemporalInterval interval2 = new AllenOperators.TemporalInterval(
            new java.util.Date(2500), new java.util.Date(3000));
        
        // Test BEFORE relationship
        assertTrue("Interval 1 should be before interval 2", 
            AllenOperators.testAllenRelation(interval1, interval2, AllenOperators.AllenRelation.BEFORE));
        
        // Test AFTER relationship
        assertTrue("Interval 2 should be after interval 1", 
            AllenOperators.testAllenRelation(interval2, interval1, AllenOperators.AllenRelation.AFTER));
        
        // Test overlapping intervals
        AllenOperators.TemporalInterval interval3 = new AllenOperators.TemporalInterval(
            new java.util.Date(1500), new java.util.Date(2500));
        
        assertTrue("Interval 1 should overlap interval 3", 
            AllenOperators.testAllenRelation(interval1, interval3, AllenOperators.AllenRelation.OVERLAPS));
        
        // Test DURING relationship
        AllenOperators.TemporalInterval interval4 = new AllenOperators.TemporalInterval(
            new java.util.Date(1200), new java.util.Date(1800));
        
        assertTrue("Interval 4 should be during interval 1", 
            AllenOperators.testAllenRelation(interval4, interval1, AllenOperators.AllenRelation.DURING));
        
        // Test EQUALS relationship
        AllenOperators.TemporalInterval interval5 = new AllenOperators.TemporalInterval(
            new java.util.Date(1000), new java.util.Date(2000));
        
        assertTrue("Interval 1 should equal interval 5", 
            AllenOperators.testAllenRelation(interval1, interval5, AllenOperators.AllenRelation.EQUALS));

        // Test MEETS relationship
        AllenOperators.TemporalInterval interval6 = new AllenOperators.TemporalInterval(
            new java.util.Date(2000), new java.util.Date(3000));
        
        assertTrue("Interval 1 should meet interval 6", 
            AllenOperators.testAllenRelation(interval1, interval6, AllenOperators.AllenRelation.MEETS));
    }

    private void setupTemporalTestGraph() {
        // Create a test temporal graph with various intervals for testing Allen relationships
        
        // Event 1: 2023-01-01 to 2023-01-31 (Meeting planning)
        Vertex v1 = graph.addVertex("event", "meeting_planning", "name", "Planning Phase");
        v1.property("startTime", "2023-01-01");
        v1.property("endTime", "2023-01-31");
        
        // Event 2: 2023-02-01 to 2023-02-28 (Meeting execution) - AFTER meeting planning
        Vertex v2 = graph.addVertex("event", "meeting_execution", "name", "Execution Phase");
        v2.property("startTime", "2023-02-01");
        v2.property("endTime", "2023-02-28");
        
        // Event 3: 2023-01-15 to 2023-01-20 (Preparation) - DURING meeting planning
        Vertex v3 = graph.addVertex("event", "preparation", "name", "Preparation");
        v3.property("startTime", "2023-01-15");
        v3.property("endTime", "2023-01-20");
        
        // Event 4: 2023-01-25 to 2023-02-05 (Follow-up) - OVERLAPS meeting planning and execution
        Vertex v4 = graph.addVertex("event", "followup", "name", "Follow-up");
        v4.property("startTime", "2023-01-25");
        v4.property("endTime", "2023-02-05");
        
        // Event 5: 2023-01-31 to 2023-02-01 (Transition) - MEETS planning with execution
        Vertex v5 = graph.addVertex("event", "transition", "name", "Transition");
        v5.property("startTime", "2023-01-31");
        v5.property("endTime", "2023-02-01");
        
        // Event 6: 2023-01-01 to 2023-01-10 (Early prep) - STARTS with meeting planning
        Vertex v6 = graph.addVertex("event", "early_prep", "name", "Early Preparation");
        v6.property("startTime", "2023-01-01");
        v6.property("endTime", "2023-01-10");
        
        // Add some temporal edges
        Edge e1 = v1.addEdge("precedes", v2, "relation", "sequential");
        e1.property("startTime", "2023-01-01");
        e1.property("endTime", "2023-02-28");
        
        Edge e2 = v1.addEdge("contains", v3, "relation", "containment");
        e2.property("startTime", "2023-01-01");
        e2.property("endTime", "2023-01-31");
        
        Edge e3 = v4.addEdge("overlaps", v1, "relation", "overlap");
        e3.property("startTime", "2023-01-25");
        e3.property("endTime", "2023-02-05");
    }

    // Test traversal implementations
    public static class Traversals extends AllenFilterStepTest {
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalBefore_refElement() {
            // Find vertices that are before the "meeting_execution" event
            Vertex refVertex = g.V().has("event", "meeting_execution").next();
            return g.V().temporalBefore(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalAfter_refElement() {
            Vertex refVertex = g.V().has("event", "meeting_planning").next();
            return g.V().temporalAfter(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalOverlaps_refElement() {
            Vertex refVertex = g.V().has("event", "meeting_planning").next();
            return g.V().temporalOverlaps(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalDuring_refElement() {
            Vertex refVertex = g.V().has("event", "meeting_planning").next();
            return g.V().temporalDuring(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalContains_refElement() {
            Vertex refVertex = g.V().has("event", "preparation").next();
            return g.V().temporalContains(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalEquals_refElement() {
            Vertex refVertex = g.V().has("event", "meeting_planning").next();
            return g.V().temporalEquals(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalMeets_refElement() {
            Vertex refVertex = g.V().has("event", "meeting_execution").next();
            return g.V().temporalMeets(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalStartedBy_refElement() {
            Vertex refVertex = g.V().has("event", "early_prep").next();
            return g.V().temporalStartedBy(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_temporalFinishes_refElement() {
            Vertex refVertex = g.V().has("event", "meeting_planning").next();
            return g.V().temporalFinishes(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_allen_before_traversal() {
            return g.V().allen(AllenOperators.AllenRelation.BEFORE, g.V().has("event", "meeting_execution"));
        }
        
        @Override
        public Traversal<Edge, Edge> get_g_E_temporalOverlaps_vertex() {
            Vertex refVertex = g.V().has("event", "meeting_planning").next();
            return g.E().temporalOverlaps(refVertex);
        }
        
        @Override
        public Traversal<Vertex, Vertex> get_g_V_allen_generic_before() {
            Vertex refVertex = g.V().has("event", "meeting_execution").next();
            return g.V().allen(AllenOperators.AllenRelation.BEFORE, refVertex);
        }
    }
}