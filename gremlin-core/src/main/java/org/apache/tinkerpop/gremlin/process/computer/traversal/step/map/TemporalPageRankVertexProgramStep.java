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
package org.apache.tinkerpop.gremlin.process.computer.traversal.step.map;

import org.apache.tinkerpop.gremlin.process.computer.GraphFilter;
import org.apache.tinkerpop.gremlin.process.computer.Memory;
import org.apache.tinkerpop.gremlin.process.computer.ranking.pagerank.TemporalPageRankVertexProgram;
import org.apache.tinkerpop.gremlin.process.computer.traversal.TraversalVertexProgram;
import org.apache.tinkerpop.gremlin.process.ranking.pagerank.TemporalPageRankAlgorithm;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.Configuring;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TemporalPageRank;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.Parameters;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.TraverserSet;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.temporal.Lifetime;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * {@link VertexProgramStep} wrapper for distributed OLAP execution of {@link TemporalPageRankVertexProgram}.
 */
public final class TemporalPageRankVertexProgramStep extends VertexProgramStep implements Configuring {

    private Parameters parameters = new Parameters();
    private double alpha = TemporalPageRankAlgorithm.DEFAULT_ALPHA;
    private double beta = TemporalPageRankAlgorithm.DEFAULT_BETA;
    private String propertyName = TemporalPageRankVertexProgram.PAGE_RANK;
    private boolean normalize = TemporalPageRankAlgorithm.DEFAULT_NORMALIZE;
    private long bucketMillis = 0L;
    private Lifetime activeWindow = null;

    public TemporalPageRankVertexProgramStep(final Traversal.Admin traversal) {
        super(traversal);
    }

    public void setActiveWindow(final Lifetime activeWindow) {
        this.activeWindow = activeWindow;
    }

    public Lifetime getActiveWindow() {
        return this.activeWindow;
    }

    @Override
    public void configure(final Object... keyValues) {
        if (null == keyValues || keyValues.length % 2 != 0) {
            this.parameters.set(null, keyValues);
            return;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            final Object key = keyValues[i];
            final Object value = keyValues[i + 1];
            if (TemporalPageRank.alpha.equals(key)) {
                if (!(value instanceof Number))
                    throw new IllegalArgumentException("TemporalPageRank.alpha requires a Number as its argument");
                this.alpha = ((Number) value).doubleValue();
            } else if (TemporalPageRank.beta.equals(key)) {
                if (!(value instanceof Number))
                    throw new IllegalArgumentException("TemporalPageRank.beta requires a Number as its argument");
                this.beta = ((Number) value).doubleValue();
            } else if (TemporalPageRank.propertyName.equals(key)) {
                if (!(value instanceof String))
                    throw new IllegalArgumentException("TemporalPageRank.propertyName requires a String as its argument");
                this.propertyName = (String) value;
            } else if (TemporalPageRank.normalize.equals(key)) {
                if (!(value instanceof Boolean))
                    throw new IllegalArgumentException("TemporalPageRank.normalize requires a Boolean as its argument");
                this.normalize = (Boolean) value;
            } else if (TemporalPageRank.bucketMillis.equals(key)) {
                this.bucketMillis = parseBucketMillis(value);
            } else {
                this.parameters.set(null, key, value);
            }
        }
    }

    private static long parseBucketMillis(final Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("TemporalPageRank.bucketMillis requires a non-negative integral Number as its argument");
        }
        if (value instanceof Double || value instanceof Float) {
            final double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d) || d < 0.0d || d != Math.rint(d) || d > Long.MAX_VALUE) {
                throw new IllegalArgumentException("TemporalPageRank.bucketMillis requires a non-negative integral Number as its argument");
            }
        }
        final long millis = ((Number) value).longValue();
        if (millis < 0L) {
            throw new IllegalArgumentException("TemporalPageRank.bucketMillis requires a non-negative integral Number as its argument");
        }
        return millis;
    }

    @Override
    public Parameters getParameters() {
        return this.parameters;
    }

    @Override
    public TemporalPageRankVertexProgram generateProgram(final Graph graph, final Memory memory) {
        final TemporalPageRankVertexProgram.Builder builder = TemporalPageRankVertexProgram.build()
                .alpha(this.alpha)
                .beta(this.beta)
                .property(this.propertyName)
                .normalize(this.normalize)
                .bucketMillis(this.bucketMillis)
                .activeWindow(this.activeWindow);

        if (this.previousTraversalVertexProgram()) {
            builder.scopedByHaltedTraversers(true);
            if (memory != null && memory.exists(TraversalVertexProgram.HALTED_TRAVERSERS)) {
                builder.haltedTraversers(memory.<TraverserSet<Vertex>>get(TraversalVertexProgram.HALTED_TRAVERSERS));
            }
        }

        return builder.create(graph);
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.OBJECT);
    }

    @Override
    public TemporalPageRankVertexProgramStep clone() {
        final TemporalPageRankVertexProgramStep clone = (TemporalPageRankVertexProgramStep) super.clone();
        clone.parameters = this.parameters.clone();
        return clone;
    }

    @Override
    public int hashCode() {
        return super.hashCode()
                ^ Double.hashCode(this.alpha)
                ^ Double.hashCode(this.beta)
                ^ this.propertyName.hashCode()
                ^ Boolean.hashCode(this.normalize)
                ^ Long.hashCode(this.bucketMillis)
                ^ Objects.hashCode(this.activeWindow)
                ^ this.parameters.hashCode();
    }

    @Override
    public String toString() {
        return StringFactory.stepString(this, this.alpha, this.beta, this.propertyName, this.normalize,
                this.bucketMillis, this.activeWindow, new GraphFilter(this.computer));
    }
}
