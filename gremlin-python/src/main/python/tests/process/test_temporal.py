#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

import pytest

from gremlin_python.process.anonymous_traversal import traversal
from gremlin_python.process.graph_traversal import __
from gremlin_python.process.translator import Translator
from gremlin_python.process.traversal import (
    Direction,
    DropMode,
    GeneralizedIntervalRelation,
    GeneralizedRelation,
    Interval,
    Lifetime,
    LifetimeMode,
    Temporal,
    TemporalOptions,
    TemporalP,
    TemporalPageRank,
    TemporalPathTarget,
    TemporalQuantifier,
)
from gremlin_python.structure.graph import Graph
from gremlin_python.structure.io.graphbinaryV1 import GraphBinaryReader, GraphBinaryWriter
from gremlin_python.structure.io.graphsonV2d0 import (
    GraphSONReader as GraphSONV2Reader,
    GraphSONWriter as GraphSONV2Writer,
)
from gremlin_python.structure.io.graphsonV3d0 import (
    GraphSONReader as GraphSONV3Reader,
    GraphSONWriter as GraphSONV3Writer,
)


class TestTemporal(object):

    def test_lifetime_normalization(self):
        lt = Lifetime([(300, 400), (100, 200), (200, 250)])
        assert lt.intervals == (Interval(100, 250), Interval(300, 400))
        assert lt.to_temporal_intervals_string() == "100:250,300:400"
        assert Lifetime.from_temporal_intervals("100:250,300:400") == lt

        # Half-open [100, 200) must NOT merge with point [200, 200]
        half_open_and_point = Lifetime([(100, 200), (200, 200)])
        assert half_open_and_point.intervals == (Interval(100, 200), Interval(200, 200))

        assert Lifetime.empty().is_empty()
        assert Lifetime.empty().to_temporal_intervals_string() == ""
        assert Lifetime.from_temporal_intervals("") == Lifetime.empty()
        assert Lifetime.unbounded().is_unbounded()

    def test_temporal_dsl_bytecode(self):
        g = traversal().with_graph(Graph())
        lt = Lifetime.from_(1000, 2000)

        t = (
            g.V()
            .at_time(1500)
            .window(1000, 2000)
            .lifetime(1000, 2000)
            .add_interval(3000, 4000)
            .drop_interval(1500, 1800, DropMode.HARD)
            .lifetime_property("role", "engineer", 1000, 2000)
            .temporal_overlaps(lt)
            .temporal_before(lt)
            .temporal_after(lt)
            .temporal_contains(lt)
            .temporal_during(lt)
            .temporal_equals(lt)
            .temporal_finishes(lt)
            .temporal_finished_by(lt)
            .temporal_meets(lt)
            .temporal_met_by(lt)
            .temporal_overlapped_by(lt)
            .temporal_starts(lt)
            .temporal_started_by(lt)
            .temporal_intersects(lt)
            .temporal_disjoint(lt)
            .continuous_path("knows")
            .sequential_path(0, 1000, "knows")
            .pairwise_continuous_path(True, "knows")
            .temporal_page_rank()
            .with_(TemporalPageRank.alpha, 0.85)
            .with_(TemporalPageRank.bucket_millis, 1000)
            .tbfs(TemporalPathTarget.EARLIEST_ARRIVAL, 0)
            .temporal_shortest_path(TemporalPathTarget.LATEST_DEPARTURE, 100)
            .get_lifetime()
        )

        step_names = [inst[0] for inst in t.bytecode.step_instructions]
        assert step_names == [
            "V",
            "atTime",
            "window",
            "lifetime",
            "addInterval",
            "dropInterval",
            "lifetimeProperty",
            "temporalOverlaps",
            "temporalBefore",
            "temporalAfter",
            "temporalContains",
            "temporalDuring",
            "temporalEquals",
            "temporalFinishes",
            "temporalFinishedBy",
            "temporalMeets",
            "temporalMetBy",
            "temporalOverlappedBy",
            "temporalStarts",
            "temporalStartedBy",
            "temporalIntersects",
            "temporalDisjoint",
            "continuousPath",
            "sequentialPath",
            "pairwiseContinuousPath",
            "temporalPageRank",
            "with",
            "with",
            "tbfs",
            "temporalShortestPath",
            "getLifetime",
        ]

        anon = __.get_start_time().get_end_time().window(lt)
        assert [inst[0] for inst in anon.bytecode.step_instructions] == ["getStartTime", "getEndTime", "window"]

    def test_temporal_p_negation_and_wire_parts(self):
        lt = Lifetime.from_(100, 200)
        assert TemporalP.intersects(lt).negate() == TemporalP.disjoint(lt)
        assert TemporalP.disjoint(lt).negate() == TemporalP.intersects(lt)
        assert TemporalP.intersects(lt).any().negate().to_wire_parts() == ("notAny", [Temporal.intersects, lt])
        assert TemporalP.overlaps(lt).negate().to_wire_parts() == ("not", [Temporal.overlaps, lt])
        assert TemporalP.overlaps(lt).any().to_wire_parts() == ("any", [Temporal.overlaps, lt])
        assert TemporalP.overlaps(lt).to_wire_parts() == ("overlaps", [lt])

        rel = GeneralizedRelation(2, 2, [0, 2])
        assert TemporalP.generalized(lt, rel).negate().to_wire_parts() == ("notGeneralized", [lt, rel])
        assert TemporalP.generalized(lt, rel).any().negate().to_wire_parts() == ("notAnyGeneralized", [lt, rel])

        between_p = TemporalP.between(Temporal.precedes, Temporal.meets, lt)
        assert between_p.generalized_interval_relation == GeneralizedIntervalRelation(
            GeneralizedRelation(2, 2, [0, 0]), GeneralizedRelation(2, 2, [0, 1])
        )

    def test_graphbinary_round_trip(self):
        writer = GraphBinaryWriter()
        reader = GraphBinaryReader()

        lt = Lifetime([(100, 200), (500, 900)])
        iv = Interval(100, 200)
        rel = GeneralizedRelation(2, 2, [0, 2])
        irel = GeneralizedIntervalRelation(GeneralizedRelation(2, 2, [0, 0]), GeneralizedRelation(2, 2, [0, 2]))
        empty_irel = GeneralizedIntervalRelation.empty(2, 2)

        samples = [
            lt,
            Lifetime.empty(),
            Lifetime.unbounded(),
            iv,
            rel,
            irel,
            empty_irel,
            LifetimeMode.REPLACE,
            LifetimeMode.ADD,
            LifetimeMode.DROP,
            DropMode.SOFT,
            DropMode.HARD,
            TemporalPathTarget.EARLIEST_ARRIVAL,
            TemporalPathTarget.LATEST_DEPARTURE,
            Temporal.overlaps,
            Temporal.overlapped_by,
            TemporalQuantifier.ALL,
            TemporalQuantifier.ANY,
            TemporalP.overlaps(lt),
            TemporalP.overlaps(lt).any(),
            TemporalP.overlaps(lt).negate(),
            TemporalP.overlaps(lt).any().negate(),
            TemporalP.generalized(lt, rel),
            TemporalP.generalized(lt, rel).any(),
            TemporalP.generalized(lt, irel).negate(),
            TemporalP.generalized(lt, empty_irel).any().negate(),
            TemporalP.between(Temporal.starts, Temporal.finishes, lt),
        ]

        for sample in samples:
            encoded = writer.write_object(sample)
            decoded = reader.read_object(encoded)
            assert decoded == sample, "GraphBinary round-trip failed for %r" % (sample,)

        # Verify 64-bit Unix epoch milliseconds (exceeding 32-bit signed int) round-trip in GraphBinary
        epoch_ms = 1700000000000
        assert reader.read_object(writer.write_object(epoch_ms)) == epoch_ms

        # Verify connective TemporalP.and_() / or_() serialize cleanly
        assert len(writer.write_object(TemporalP.overlaps(lt).any().and_(TemporalP.during(lt)))) > 0
        assert len(writer.write_object(TemporalP.overlaps(lt).negate().or_(TemporalP.meets(lt)))) > 0

        # Verify GraphBinary bytecode round-trip
        g = traversal().with_graph(Graph())
        bc = (
            g.V()
            .at_time(epoch_ms)
            .window(lt)
            .has("life", TemporalP.overlaps(lt).any().negate())
            .drop_interval(100, 150, DropMode.HARD)
            .tbfs(0, TemporalPathTarget.EARLIEST_ARRIVAL, Direction.OUT, "knows")
            .bytecode
        )
        encoded_bc = writer.write_object(bc)
        decoded_bc = reader.read_object(encoded_bc)
        assert decoded_bc == bc

    def test_graphson_v3_and_v2_round_trip(self):
        lt = Lifetime([(100, 200), (500, 900)])
        iv = Interval(100, 200)
        rel = GeneralizedRelation(2, 2, [0, 2])
        irel = GeneralizedIntervalRelation(GeneralizedRelation(2, 2, [0, 0]), GeneralizedRelation(2, 2, [0, 2]))
        empty_irel = GeneralizedIntervalRelation.empty(2, 2)

        samples = [
            lt,
            Lifetime.empty(),
            iv,
            rel,
            irel,
            empty_irel,
            LifetimeMode.ADD,
            DropMode.HARD,
            TemporalPathTarget.EARLIEST_ARRIVAL,
            Temporal.overlappedBy,
            TemporalQuantifier.ANY,
            TemporalP.overlaps(lt),
            TemporalP.overlaps(lt).any(),
            TemporalP.overlaps(lt).negate(),
            TemporalP.overlaps(lt).any().negate(),
            TemporalP.generalized(lt, rel),
            TemporalP.generalized(lt, irel).any().negate(),
        ]

        for WriterCls, ReaderCls in [(GraphSONV3Writer, GraphSONV3Reader), (GraphSONV2Writer, GraphSONV2Reader)]:
            writer = WriterCls()
            reader = ReaderCls()
            for sample in samples:
                payload = writer.write_object(sample)
                decoded = reader.read_object(payload)
                assert decoded == sample, "%s round-trip failed for %r" % (WriterCls.__name__, sample)

        # Verify exact Java GraphSON type tag for Quantifier
        q_dict = GraphSONV3Writer().to_dict(TemporalQuantifier.ANY)
        assert q_dict == {"@type": "g:Quantifier", "@value": "ANY"}

    def test_translator_temporal_steps(self):
        g = traversal().with_graph(Graph())
        lt = Lifetime.from_(1000, 2000)
        t = (
            g.V()
            .window(lt)
            .where(__.get_lifetime().is_(TemporalP.overlaps(lt).negate()))
            .drop_interval(1000, 1500, DropMode.HARD)
            .with_(TemporalOptions.quantifier, TemporalQuantifier.ANY)
        )
        script = Translator("g").translate(t.bytecode)
        assert "window(Lifetime.fromTemporalIntervals('1000:2000'))" in script
        assert "TemporalP.overlaps(Lifetime.fromTemporalIntervals('1000:2000')).negate()" in script
        assert "DropMode.HARD" in script
        assert "Temporal.Quantifier.ANY" in script

        unbounded_script = Translator("g").translate(g.V().window(Lifetime.unbounded()).bytecode)
        assert "window(Lifetime.UNBOUNDED)" in unbounded_script

    def test_validation_and_epoch_millis_edge_cases(self):
        with pytest.raises(ValueError):
            TemporalP.between(Temporal.succeeds, Temporal.precedes, Lifetime.from_(100, 200))
        with pytest.raises(ValueError):
            GeneralizedIntervalRelation(GeneralizedRelation(2, 2, [2, 4]), GeneralizedRelation(2, 2, [0, 2]))
        with pytest.raises(ValueError):
            Interval(-9223372036854775809, 100)
        with pytest.raises(ValueError):
            Interval(0, 9223372036854775808)

        # Sub-millisecond truncation (matching Java Instant.toEpochMilli()) and lowercase 'z'
        iv = Interval("1970-01-01T00:00:01.000900z", "1970-01-01T00:00:02.5Z")
        assert iv.start_millis == 1000
        assert iv.end_millis == 2500

        # 2-element list/tuple unpacking in TemporalP.generalized
        lt = Lifetime.from_(100, 200)
        rel = GeneralizedRelation(2, 2, [0, 2])
        unpacked = TemporalP.generalized([lt, rel])
        assert unpacked.value == lt
        assert unpacked.generalized_relation == rel

