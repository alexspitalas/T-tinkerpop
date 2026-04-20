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

import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.AbstractStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.process.traversal.util.FastNoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.util.DatetimeHelper;

import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Traversal integration for Rozenshtein-Gionis Temporal PageRank.
 * <p>
 * The step gathers temporal edges from the graph, parses their {@code startTime}/{@code endTime} lifetime,
 * delegates the chronological lifetime-start scan to {@link TemporalPageRankAlgorithm}, and writes the resulting
 * rank to each vertex.
 */
public final class TemporalPageRankStep<S> extends AbstractStep<S, S> implements Configuring {

    private static final String[] LIFETIME_DATE_FORMATS = {
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

    private Parameters parameters = new Parameters();
    private TraverserSet<S> barrier;
    private boolean computed = false;
    private double alpha = TemporalPageRankAlgorithm.DEFAULT_ALPHA;
    private double beta = TemporalPageRankAlgorithm.DEFAULT_BETA;
    private String startTimeProperty = TemporalPageRankAlgorithm.DEFAULT_START_TIME_PROPERTY;
    private String endTimeProperty = TemporalPageRankAlgorithm.DEFAULT_END_TIME_PROPERTY;
    private String propertyName = TemporalPageRankAlgorithm.TEMPORAL_PAGE_RANK;
    private boolean normalize = TemporalPageRankAlgorithm.DEFAULT_NORMALIZE;

    public TemporalPageRankStep(final Traversal.Admin traversal) {
        super(traversal);
        this.barrier = (TraverserSet<S>) this.traversal.getTraverserSetSupplier().get();
    }

    @Override
    public void configure(final Object... keyValues) {
        if (keyValues[0].equals(TemporalPageRank.alpha)) {
            if (!(keyValues[1] instanceof Number))
                throw new IllegalArgumentException("TemporalPageRank.alpha requires a Number as its argument");
            this.alpha = ((Number) keyValues[1]).doubleValue();
        } else if (keyValues[0].equals(TemporalPageRank.beta)) {
            if (!(keyValues[1] instanceof Number))
                throw new IllegalArgumentException("TemporalPageRank.beta requires a Number as its argument");
            this.beta = ((Number) keyValues[1]).doubleValue();
        } else if (keyValues[0].equals(TemporalPageRank.startTimeProperty)) {
            if (!(keyValues[1] instanceof String))
                throw new IllegalArgumentException("TemporalPageRank.startTimeProperty requires a String as its argument");
            this.startTimeProperty = (String) keyValues[1];
        } else if (keyValues[0].equals(TemporalPageRank.endTimeProperty)) {
            if (!(keyValues[1] instanceof String))
                throw new IllegalArgumentException("TemporalPageRank.endTimeProperty requires a String as its argument");
            this.endTimeProperty = (String) keyValues[1];
        } else if (keyValues[0].equals(TemporalPageRank.propertyName)) {
            if (!(keyValues[1] instanceof String))
                throw new IllegalArgumentException("TemporalPageRank.propertyName requires a String as its argument");
            this.propertyName = (String) keyValues[1];
        } else if (keyValues[0].equals(TemporalPageRank.normalize)) {
            if (!(keyValues[1] instanceof Boolean))
                throw new IllegalArgumentException("TemporalPageRank.normalize requires a Boolean as its argument");
            this.normalize = (Boolean) keyValues[1];
        } else {
            this.parameters.set(null, keyValues);
        }
    }

    @Override
    public Parameters getParameters() {
        return parameters;
    }

    @Override
    protected Traverser.Admin<S> processNextStart() {
        if (!computed)
            processAllStarts();

        if (barrier.isEmpty())
            throw FastNoSuchElementException.instance();
        return barrier.remove();
    }

    private void processAllStarts() {
        computed = true;

        while (this.starts.hasNext()) {
            final Traverser.Admin<S> traverser = this.starts.next();
            if (!(traverser.get() instanceof Vertex))
                throw new IllegalArgumentException("temporalPageRank() can only be applied to vertex traversers");
            traverser.setStepId(this.getNextStep().getId());
            this.barrier.add(traverser);
        }

        if (this.barrier.isEmpty())
            return;

        final Graph graph = this.getTraversal().getGraph().orElseThrow(
                () -> new IllegalStateException("temporalPageRank() requires a traversal with an attached graph"));
        final TemporalPageRankAlgorithm.Config config =
                new TemporalPageRankAlgorithm.Config(alpha, beta, startTimeProperty, endTimeProperty, propertyName, normalize);
        final List<TemporalPageRankAlgorithm.TemporalEdge> edges = readTemporalEdges(
                graph, config.getStartTimeProperty(), config.getEndTimeProperty());

        final Map<Object, Double> ranks = new TemporalPageRankAlgorithm(config).execute(edges);
        writeRanks(graph, config.getPropertyName(), ranks);
    }

    private List<TemporalPageRankAlgorithm.TemporalEdge> readTemporalEdges(final Graph graph,
                                                                           final String startTimeProperty,
                                                                           final String endTimeProperty) {
        final List<TemporalPageRankAlgorithm.TemporalEdge> temporalEdges = new ArrayList<>();
        final Iterator<Edge> edges = graph.edges();

        try {
            while (edges.hasNext()) {
                temporalEdges.add(toTemporalEdge(edges.next(), startTimeProperty, endTimeProperty));
            }
        } finally {
            CloseableIterator.closeIterator(edges);
        }

        return temporalEdges;
    }

    private TemporalPageRankAlgorithm.TemporalEdge toTemporalEdge(final Edge edge, final String startTimeProperty,
                                                                  final String endTimeProperty) {
        final Property<Object> startTime = edge.property(startTimeProperty);
        if (!startTime.isPresent())
            throw new IllegalArgumentException("Temporal edge " + edge.id() +
                    " is missing start time property '" + startTimeProperty + "'");

        final Property<Object> endTime = edge.property(endTimeProperty);
        if (!endTime.isPresent())
            throw new IllegalArgumentException("Temporal edge " + edge.id() +
                    " is missing end time property '" + endTimeProperty + "'");

        return new TemporalPageRankAlgorithm.TemporalEdge(edge.outVertex().id(), edge.inVertex().id(), edge.id(),
                parseLifetimeValue(edge, startTimeProperty, startTime.value()),
                parseLifetimeValue(edge, endTimeProperty, endTime.value()));
    }

    private Instant parseLifetimeValue(final Edge edge, final String propertyName, final Object value) {
        if (null == value)
            throw new IllegalArgumentException("Temporal edge " + edge.id() + " has a null '" + propertyName + "' value");
        if (value instanceof Instant)
            return (Instant) value;
        if (value instanceof Date)
            return ((Date) value).toInstant();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return Instant.ofEpochMilli(((Number) value).longValue());
        if (value instanceof BigInteger) {
            try {
                return Instant.ofEpochMilli(((BigInteger) value).longValueExact());
            } catch (final ArithmeticException e) {
                throw new IllegalArgumentException("Temporal edge " + edge.id() +
                        " has an unparseable '" + propertyName + "' value: " + value, e);
            }
        }
        if (value instanceof String) {
            if (LifetimeStep.DEFAULT_ENDTIME.equals(value))
                return Instant.ofEpochMilli(Long.MAX_VALUE);
            try {
                return Instant.ofEpochMilli(Long.parseLong((String) value));
            } catch (final NumberFormatException ignored) {
                // try date/time parsing next
            }
            try {
                return DatetimeHelper.parse((String) value).toInstant();
            } catch (final DateTimeParseException e) {
                final Instant parsed = parseLifetimeStepDate((String) value);
                if (null != parsed)
                    return parsed;
                throw new IllegalArgumentException("Temporal edge " + edge.id() +
                        " has an unparseable '" + propertyName + "' value: " + value, e);
            }
        }

        throw new IllegalArgumentException("Temporal edge " + edge.id() + " has an unparseable '" + propertyName +
                "' value of type " + value.getClass().getName());
    }

    private Instant parseLifetimeStepDate(final String value) {
        for (final String format : LIFETIME_DATE_FORMATS) {
            try {
                final java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(format);
                sdf.setLenient(false);
                return sdf.parse(value).toInstant();
            } catch (final java.text.ParseException ignored) {
                // try next format
            }
        }

        return null;
    }

    private void writeRanks(final Graph graph, final String propertyName, final Map<Object, Double> ranks) {
        final Iterator<Vertex> vertices = graph.vertices();

        try {
            while (vertices.hasNext()) {
                final Vertex vertex = vertices.next();
                vertex.property(VertexProperty.Cardinality.single, propertyName, ranks.getOrDefault(vertex.id(), 0.0d));
            }
        } finally {
            CloseableIterator.closeIterator(vertices);
        }
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.OBJECT);
    }

    @Override
    public TemporalPageRankStep<S> clone() {
        final TemporalPageRankStep<S> clone = (TemporalPageRankStep<S>) super.clone();
        clone.parameters = this.parameters.clone();
        clone.barrier = (TraverserSet<S>) this.traversal.getTraverserSetSupplier().get();
        clone.computed = false;
        return clone;
    }

    @Override
    public void reset() {
        super.reset();
        this.barrier.clear();
        this.computed = false;
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ Double.hashCode(this.alpha) ^ Double.hashCode(this.beta) ^
                this.startTimeProperty.hashCode() ^ this.endTimeProperty.hashCode() ^
                this.propertyName.hashCode() ^ Boolean.hashCode(this.normalize);
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.alpha, this.beta, this.startTimeProperty, this.endTimeProperty,
                this.propertyName, this.normalize);
    }
}
