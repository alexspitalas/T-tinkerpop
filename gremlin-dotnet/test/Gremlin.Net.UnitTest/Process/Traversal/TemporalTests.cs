#region License

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#endregion

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Gremlin.Net.Process.Traversal;
using Gremlin.Net.Process.Traversal.Translator;
using Gremlin.Net.Structure.IO.GraphBinary;
using Gremlin.Net.Structure.IO.GraphSON;
using Xunit;

namespace Gremlin.Net.UnitTest.Process.Traversal
{
    public class TemporalTests
    {
        private readonly GraphTraversalSource _g = AnonymousTraversalSource.Traversal();

        [Fact]
        public void ShouldValidateAndNormalizeIntervalAndLifetime()
        {
            var iv = new Interval(100, 200);
            Assert.Equal("100:200", iv.ToGraphSONString());
            Assert.Equal(iv, Interval.FromGraphSONString("100:200"));
            Assert.Equal("Interval(100, 200)", iv.ToString());

            Assert.Throws<ArgumentException>(() => new Interval(200, 100));
            Assert.Throws<ArgumentException>(() => Interval.FromGraphSONString("100"));
            Assert.Throws<ArgumentNullException>(() => Interval.FromGraphSONString(null!));

            var lt = Lifetime.Of(
                new Interval(300, 400),
                new Interval(100, 200),
                new Interval(200, 250));
            Assert.Equal("100:250,300:400", lt.ToTemporalIntervalsString());
            Assert.Equal("Lifetime(100:250,300:400)", lt.ToString());
            Assert.Equal(lt, Lifetime.FromTemporalIntervals(" 300:400, 100:200 , 200:250 "));

            // Half-open [100, 200) must not merge with point [200, 200]
            var halfOpenAndPoint = Lifetime.Of(new Interval(100, 200), new Interval(200, 200));
            Assert.Equal("100:200,200:200", halfOpenAndPoint.ToTemporalIntervalsString());

            Assert.True(Lifetime.Empty.IsEmpty);
            Assert.Equal("Lifetime.EMPTY", Lifetime.Empty.ToString());
            Assert.True(Lifetime.Unbounded.IsUnbounded);
            Assert.Equal("Lifetime.UNBOUNDED", Lifetime.Unbounded.ToString());
            Assert.Equal(Lifetime.Empty, Lifetime.FromTemporalIntervals(""));
            Assert.Equal(Lifetime.Empty, Lifetime.FromTemporalIntervals(null));

            Assert.Throws<ArgumentNullException>(() => new Lifetime(new Interval[] { null! }));
        }

        [Fact]
        public void ShouldValidateGeneralizedRelationAndIntervalRelation()
        {
            var overlaps = GeneralizedRelation.FromAllen(Temporal.Overlaps);
            Assert.Equal(new GeneralizedRelation(2, 2, 0, 2), overlaps);
            Assert.Equal("2,2:0,2", overlaps.ToGraphSONString());
            Assert.Equal("2,2:0,2", overlaps.ToString());
            Assert.Equal(overlaps, GeneralizedRelation.FromGraphSONString("2,2:0,2"));

            Assert.Throws<ArgumentNullException>(() => GeneralizedRelation.FromAllen(null!));
            Assert.Throws<ArgumentException>(() => GeneralizedRelation.FromAllen(Temporal.Intersects));
            Assert.Throws<ArgumentException>(() => new GeneralizedRelation(0, 2));
            Assert.Throws<ArgumentException>(() => new GeneralizedRelation(2, 2, 0));
            Assert.Throws<ArgumentException>(() => new GeneralizedRelation(2, 2, 3, 2));
            Assert.Throws<ArgumentException>(() => new GeneralizedRelation(2, 2, 1, 1));
            Assert.Throws<ArgumentException>(() => new GeneralizedRelation(2, 2, 0, 5));

            var lower = GeneralizedRelation.FromAllen(Temporal.Precedes);
            var upper = GeneralizedRelation.FromAllen(Temporal.Overlaps);
            var gir = new GeneralizedIntervalRelation(lower, upper);
            Assert.False(gir.IsEmpty);
            Assert.Equal("2,2:0,0:0,2", gir.ToGraphSONString());
            Assert.Equal("2,2:0,0:0,2", gir.ToString());
            Assert.Equal(gir, GeneralizedIntervalRelation.FromGraphSONString("2,2:0,0:0,2"));

            var emptyGir = GeneralizedIntervalRelation.Empty(2, 2);
            Assert.True(emptyGir.IsEmpty);
            Assert.Equal("2,2:EMPTY", emptyGir.ToGraphSONString());
            Assert.Equal("2,2:EMPTY", emptyGir.ToString());
            Assert.Equal(emptyGir, GeneralizedIntervalRelation.FromGraphSONString("2,2:EMPTY"));

            // Reject lattice-order violations (lower > upper or incomparable)
            Assert.Throws<ArgumentException>(() => new GeneralizedIntervalRelation(upper, lower));
            var contains = GeneralizedRelation.FromAllen(Temporal.Contains); // (0, 4)
            var during = GeneralizedRelation.FromAllen(Temporal.During);     // (2, 2)
            Assert.Throws<ArgumentException>(() => new GeneralizedIntervalRelation(contains, during));
            Assert.Throws<ArgumentException>(() => new GeneralizedIntervalRelation(during, contains));

            // Reject malformed EMPTY strings with trailing fields
            Assert.Throws<ArgumentException>(() => GeneralizedIntervalRelation.FromGraphSONString("2,2:EMPTY:extra"));
        }

        [Fact]
        public void ShouldBuildAndTransformTemporalP()
        {
            var lt = Lifetime.From(100, 200);
            var rel = new GeneralizedRelation(2, 2, 0, 2);
            var gir = new GeneralizedIntervalRelation(
                GeneralizedRelation.FromAllen(Temporal.Precedes),
                GeneralizedRelation.FromAllen(Temporal.Meets));

            Assert.Throws<ArgumentNullException>(() => new TemporalP(null!, lt));

            Assert.Equal(TemporalP.Disjoint(lt), TemporalP.Intersects(lt).Negate());
            Assert.Equal(TemporalP.Intersects(lt), TemporalP.Disjoint(lt).Negate());

            var anyIntersectsNeg = TemporalP.Intersects(lt).Any().Negate();
            Assert.Equal(Temporal.Intersects, anyIntersectsNeg.BiPredicate);
            Assert.Equal(TemporalQuantifier.Any, anyIntersectsNeg.Quantifier);
            Assert.True(anyIntersectsNeg.IsNegated);

            var allOverlaps = TemporalP.Overlaps(lt).Any().All();
            Assert.Equal(TemporalQuantifier.All, allOverlaps.Quantifier);

            var between = TemporalP.Between(Temporal.Precedes, Temporal.Meets, lt);
            Assert.Equal(Temporal.Generalized, between.BiPredicate);
            Assert.Equal(gir, between.GeneralizedIntervalRelation);

            Assert.Equal(TemporalP.Overlaps(lt).Any(), TemporalP.Any(Temporal.Overlaps, lt));
            Assert.Equal(TemporalP.Overlaps(lt).All(), TemporalP.All(Temporal.Overlaps, lt));
            Assert.Equal(TemporalP.Overlaps(lt).Negate(), TemporalP.Not(Temporal.Overlaps, lt));
            Assert.Equal(TemporalP.Overlaps(lt).Any().Negate(), TemporalP.NotAny(Temporal.Overlaps, lt));

            Assert.Equal(TemporalP.Generalized(lt, rel).Any(), TemporalP.AnyGeneralized(lt, rel));
            Assert.Equal(TemporalP.Generalized(lt, rel).Negate(), TemporalP.NotGeneralized(lt, rel));
            Assert.Equal(TemporalP.Generalized(lt, rel).Any().Negate(), TemporalP.NotAnyGeneralized(lt, rel));
            Assert.Equal(TemporalP.Generalized(lt, gir).Any().Negate(), TemporalP.NotAnyGeneralized(lt, gir));

            // Readable and distinct ToString() representations
            Assert.Equal("overlaps(Lifetime(100:200))", TemporalP.Overlaps(lt).ToString());
            Assert.Equal("generalized(Lifetime(100:200), 2,2:0,2)", TemporalP.Generalized(lt, rel).ToString());
            Assert.Equal("generalized(Lifetime(100:200), 2,2:0,0:0,1)", between.ToString());
            Assert.NotEqual(
                TemporalP.Overlaps(Lifetime.From(100, 200)).ToString(),
                TemporalP.Overlaps(Lifetime.From(300, 400)).ToString());
        }

        [Fact]
        public async Task ShouldRoundTripAllTemporalTypesInGraphBinary()
        {
            var writer = new GraphBinaryWriter();
            var reader = new GraphBinaryReader();

            var lt = Lifetime.Of(new Interval(100, 200), new Interval(500, 900));
            var iv = new Interval(100, 200);
            var rel = new GeneralizedRelation(2, 2, 0, 2);
            var gir = new GeneralizedIntervalRelation(
                GeneralizedRelation.FromAllen(Temporal.Precedes),
                GeneralizedRelation.FromAllen(Temporal.Overlaps));
            var emptyGir = GeneralizedIntervalRelation.Empty(2, 2);

            var samples = new object[]
            {
                lt,
                Lifetime.Empty,
                Lifetime.Unbounded,
                iv,
                rel,
                gir,
                emptyGir,
                LifetimeMode.Replace,
                LifetimeMode.Add,
                LifetimeMode.Drop,
                DropMode.Soft,
                DropMode.Hard,
                TemporalPathTarget.EarliestArrival,
                TemporalPathTarget.EarliestDeparture,
                TemporalPathTarget.LatestDeparture,
                TemporalPathTarget.LatestArrival,
                Temporal.Intersects,
                Temporal.Disjoint,
                Temporal.Overlaps,
                Temporal.OverlappedBy,
                Temporal.Generalized,
                TemporalQuantifier.All,
                TemporalQuantifier.Any,
                TemporalP.Overlaps(lt),
                TemporalP.Overlaps(lt).Any(),
                TemporalP.Overlaps(lt).Negate(),
                TemporalP.Overlaps(lt).Any().Negate(),
                TemporalP.Intersects(lt).Negate(),
                TemporalP.Generalized(lt),
                TemporalP.Generalized(lt, rel),
                TemporalP.Generalized(lt, rel).Any(),
                TemporalP.Generalized(lt, gir).Negate(),
                TemporalP.Generalized(lt, emptyGir).Any().Negate(),
                TemporalP.Between(Temporal.Precedes, Temporal.Meets, lt),
            };

            foreach (var sample in samples)
            {
                using var stream = new MemoryStream();
                await writer.WriteAsync(sample, stream);
                stream.Position = 0;
                var actual = await reader.ReadAsync(stream);
                Assert.Equal(sample, actual);
                Assert.Equal(stream.Length, stream.Position);
            }
        }

        [Fact]
        public async Task ShouldRejectTruncatedOrOversizedTemporalGraphBinaryPayloads()
        {
            var reader = new GraphBinaryReader();

            // Lifetime (0x31) with count = 1,000,000 but 0 remaining bytes
            var hugeLifetime = new byte[] { 0x31, 0x00, 0x00, 0x0F, 0x42, 0x40 };
            using (var stream = new MemoryStream(hugeLifetime))
            {
                await Assert.ThrowsAsync<IOException>(() => reader.ReadAsync(stream));
            }

            // GeneralizedRelation (0x38) with p = 1,000,000, q = 2, 0 zone bytes
            var hugeGr = new byte[] { 0x38, 0x00, 0x00, 0x0F, 0x42, 0x40, 0x00, 0x00, 0x00, 0x02 };
            using (var stream = new MemoryStream(hugeGr))
            {
                await Assert.ThrowsAsync<IOException>(() => reader.ReadAsync(stream));
            }

            // GeneralizedIntervalRelation (0x39) non-empty with p = 1,000,000, q = 2, 0 zone bytes
            var hugeGir = new byte[] { 0x39, 0x00, 0x00, 0x0F, 0x42, 0x40, 0x00, 0x00, 0x00, 0x02, 0x00 };
            using (var stream = new MemoryStream(hugeGir))
            {
                await Assert.ThrowsAsync<IOException>(() => reader.ReadAsync(stream));
            }

            // Interval (0x37) truncated to 4 bytes
            var truncatedIv = new byte[] { 0x37, 0x00, 0x00, 0x00, 0x00, 0x01 };
            using (var stream = new MemoryStream(truncatedIv))
            {
                await Assert.ThrowsAsync<IOException>(() => reader.ReadAsync(stream));
            }

            // TemporalP (0x30) with predicate "eq" and argsCount = 1,000,000, 0 remaining bytes
            var hugeTp = new byte[]
            {
                0x30, 0x00,
                0x00, 0x00, 0x00, 0x02, (byte)'e', (byte)'q',
                0x00, 0x0F, 0x42, 0x40
            };
            using (var stream = new MemoryStream(hugeTp))
            {
                await Assert.ThrowsAsync<IOException>(() => reader.ReadAsync(stream));
            }
        }

        [Theory]
        [InlineData(2)]
        [InlineData(3)]
        public void ShouldRoundTripAllTemporalTypesInGraphSON(int version)
        {
            GraphSONWriter writer = version == 3 ? (GraphSONWriter)new GraphSON3Writer() : new GraphSON2Writer();
            GraphSONReader reader = version == 3 ? (GraphSONReader)new GraphSON3Reader() : new GraphSON2Reader();

            var lt = Lifetime.Of(new Interval(100, 200), new Interval(500, 900));
            var iv = new Interval(100, 200);
            var rel = new GeneralizedRelation(2, 2, 0, 2);
            var gir = new GeneralizedIntervalRelation(
                GeneralizedRelation.FromAllen(Temporal.Precedes),
                GeneralizedRelation.FromAllen(Temporal.Overlaps));
            var emptyGir = GeneralizedIntervalRelation.Empty(2, 2);

            var samples = new object[]
            {
                lt,
                Lifetime.Empty,
                Lifetime.Unbounded,
                iv,
                rel,
                gir,
                emptyGir,
                LifetimeMode.Replace,
                LifetimeMode.Add,
                LifetimeMode.Drop,
                DropMode.Soft,
                DropMode.Hard,
                TemporalPathTarget.EarliestArrival,
                TemporalPathTarget.LatestDeparture,
                Temporal.Overlaps,
                Temporal.OverlappedBy,
                TemporalQuantifier.All,
                TemporalQuantifier.Any,
                TemporalP.Overlaps(lt),
                TemporalP.Overlaps(lt).Any(),
                TemporalP.Overlaps(lt).Negate(),
                TemporalP.Overlaps(lt).Any().Negate(),
                TemporalP.Generalized(lt, rel),
                TemporalP.Generalized(lt, gir).Any().Negate(),
                TemporalP.Generalized(lt, emptyGir),
                TemporalP.Between(Temporal.Starts, Temporal.Finishes, lt),
            };

            foreach (var sample in samples)
            {
                var json = writer.WriteObject(sample);
                var element = JsonSerializer.Deserialize<JsonElement>(json);
                object? actual = reader.ToObject(element);
                Assert.Equal(sample, actual);
            }
        }

        [Fact]
        public void ShouldTranslateTemporalTypesWithGroovyTranslator()
        {
            var translator = GroovyTranslator.Of("g");
            var lt = Lifetime.Of(new Interval(100, 200), new Interval(500, 900));
            var iv = new Interval(100, 200);
            var rel = new GeneralizedRelation(2, 2, 0, 2);
            var gir = new GeneralizedIntervalRelation(
                GeneralizedRelation.FromAllen(Temporal.Precedes),
                GeneralizedRelation.FromAllen(Temporal.Meets));
            var emptyGir = GeneralizedIntervalRelation.Empty(2, 2);

            Assert.Equal("g.inject(Lifetime.empty())", translator.Translate(_g.Inject(Lifetime.Empty)));
            Assert.Equal("g.inject(Lifetime.UNBOUNDED)", translator.Translate(_g.Inject(Lifetime.Unbounded)));
            Assert.Equal("g.inject(Lifetime.fromTemporalIntervals('100:200,500:900'))", translator.Translate(_g.Inject(lt)));
            Assert.Equal("g.inject(new Lifetime.Interval(100L,200L))", translator.Translate(_g.Inject(iv)));
            Assert.Equal("g.inject(new GeneralizedRelation(2,2,0,2))", translator.Translate(_g.Inject(rel)));
            Assert.Equal(
                "g.inject(new GeneralizedIntervalRelation(new GeneralizedRelation(2,2,0,0),new GeneralizedRelation(2,2,0,1)))",
                translator.Translate(_g.Inject(gir)));
            Assert.Equal("g.inject(GeneralizedIntervalRelation.empty(2,2))", translator.Translate(_g.Inject(emptyGir)));

            Assert.Equal("g.inject(LifetimeMode.ADD)", translator.Translate(_g.Inject(LifetimeMode.Add)));
            Assert.Equal("g.inject(DropMode.HARD)", translator.Translate(_g.Inject(DropMode.Hard)));
            Assert.Equal("g.inject(TemporalPathTarget.EARLIEST_ARRIVAL)",
                translator.Translate(_g.Inject(TemporalPathTarget.EarliestArrival)));
            Assert.Equal("g.inject(Temporal.overlaps)", translator.Translate(_g.Inject(Temporal.Overlaps)));
            Assert.Equal("g.inject(Temporal.Quantifier.ANY)", translator.Translate(_g.Inject(TemporalQuantifier.Any)));

            Assert.Equal(
                "g.V().has('life', TemporalP.overlaps(Lifetime.fromTemporalIntervals('100:200,500:900')))",
                translator.Translate(_g.V().Has("life", TemporalP.Overlaps(lt))));
            Assert.Equal(
                "g.V().has('life', TemporalP.overlaps(Lifetime.fromTemporalIntervals('100:200,500:900')).any().negate())",
                translator.Translate(_g.V().Has("life", TemporalP.Overlaps(lt).Any().Negate())));
            Assert.Equal(
                "g.V().has('life', TemporalP.generalized(Lifetime.fromTemporalIntervals('100:200,500:900'),new GeneralizedRelation(2,2,0,2)).any())",
                translator.Translate(_g.V().Has("life", TemporalP.Generalized(lt, rel).Any())));
            Assert.Equal(
                "g.V().has('life', TemporalP.generalized(Lifetime.fromTemporalIntervals('100:200,500:900'),new GeneralizedIntervalRelation(new GeneralizedRelation(2,2,0,0),new GeneralizedRelation(2,2,0,1))).negate())",
                translator.Translate(_g.V().Has("life", TemporalP.Between(Temporal.Precedes, Temporal.Meets, lt).Negate())));
        }

        [Fact]
        public void ShouldBuildTemporalTraversalBytecode()
        {
            var lt = Lifetime.From(1000, 2000);

            var traversal = _g.V()
                .AtTime(1500L)
                .Window(1000L, 2000L)
                .Window(lt)
                .Lifetime(1000L, 2000L)
                .AddInterval(3000L, 4000L)
                .DropInterval(1500L, 1800L, DropMode.Hard)
                .LifetimeProperty("role", "engineer", 1000L, 2000L)
                .TemporalOverlaps(lt).With(TemporalOptions.Quantifier, TemporalQuantifier.Any)
                .TemporalBefore(lt).With(TemporalOptions.Any)
                .TemporalAfter(lt)
                .TemporalContains(lt)
                .TemporalDuring(lt)
                .TemporalEquals(lt)
                .TemporalFinishes(lt)
                .TemporalFinishedBy(lt)
                .TemporalMeets(lt)
                .TemporalMetBy(lt)
                .TemporalOverlappedBy(lt)
                .TemporalStarts(lt)
                .TemporalStartedBy(lt)
                .TemporalIntersects(lt)
                .TemporalDisjoint(lt)
                .ContinuousPath("knows")
                .SequentialPath(0L, 1000L, "knows")
                .PairwiseContinuousPath(true, "knows")
                .TemporalPageRank()
                .With(TemporalPageRank.Alpha, 0.85)
                .With(TemporalPageRank.BucketMillis, 1000L)
                .Tbfs(TemporalPathTarget.EarliestArrival, 0L);

            var expectedSteps = new[]
            {
                "V", "atTime", "window", "window", "lifetime", "addInterval", "dropInterval", "lifetimeProperty",
                "temporalOverlaps", "with", "temporalBefore", "with", "temporalAfter", "temporalContains",
                "temporalDuring", "temporalEquals", "temporalFinishes", "temporalFinishedBy", "temporalMeets",
                "temporalMetBy", "temporalOverlappedBy", "temporalStarts", "temporalStartedBy",
                "temporalIntersects", "temporalDisjoint", "continuousPath", "sequentialPath",
                "pairwiseContinuousPath", "temporalPageRank", "with", "with", "tbfs"
            };

            Assert.Equal(expectedSteps, traversal.Bytecode.StepInstructions.Select(i => i.OperatorName).ToArray());

            var sp = _g.V()
                .TemporalShortestPath(TemporalPathTarget.LatestDeparture, 100L)
                .With(TemporalShortestPath.MaxDistance, 3)
                .With(TemporalShortestPath.IncludeEdges, true);
            Assert.Equal(new[] { "V", "temporalShortestPath", "with", "with" },
                sp.Bytecode.StepInstructions.Select(i => i.OperatorName).ToArray());

            var anon = __.GetStartTime().GetEndTime().GetLifetime().Window(lt);
            Assert.Equal(new[] { "getStartTime", "getEndTime", "getLifetime", "window" },
                anon.Bytecode.StepInstructions.Select(i => i.OperatorName).ToArray());
        }
    }
}
