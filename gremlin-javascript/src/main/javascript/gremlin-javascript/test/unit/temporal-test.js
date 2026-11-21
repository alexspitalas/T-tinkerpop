/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

'use strict';

const assert = require('assert');
const gremlin = require('../../index');
const graphBinary = require('../../lib/structure/io/binary/GraphBinary');

const {
  GraphTraversalSource,
  statics: __,
  TemporalP,
  Interval,
  Lifetime,
  GeneralizedRelation,
  GeneralizedIntervalRelation,
  TemporalPageRank,
  TemporalShortestPath,
  temporalOptions,
  lifetimeMode,
  dropMode,
  temporalPathTarget,
  temporal,
  temporalQuantifier,
  direction,
  traversal,
} = gremlin.process;
const { Graph } = gremlin.structure;
const { GraphSON3Writer, GraphSON3Reader, GraphSON2Writer, GraphSON2Reader } = gremlin.structure.io;

describe('Temporal Support (T-TinkerPop)', function () {
  it('should normalize Lifetimes matching Java half-open/point semantics', function () {
    const lt = new Lifetime([
      [300, 400],
      [100, 200],
      [200, 250],
    ]);
    assert.strictEqual(lt.toTemporalIntervalsString(), '100:250,300:400');
    assert.ok(Lifetime.fromTemporalIntervals('100:250,300:400').equals(lt));

    // Half-open [100, 200) must not merge with point [200, 200]
    const halfOpenAndPoint = new Lifetime([
      [100, 200],
      [200, 200],
    ]);
    assert.strictEqual(halfOpenAndPoint.intervals.length, 2);
    assert.strictEqual(halfOpenAndPoint.toTemporalIntervalsString(), '100:200,200:200');

    assert.ok(Lifetime.empty().isEmpty());
    assert.strictEqual(Lifetime.empty().toTemporalIntervalsString(), '');
    assert.ok(Lifetime.fromTemporalIntervals('').equals(Lifetime.empty()));
    assert.ok(Lifetime.unbounded().isUnbounded());
  });

  it('should build temporal bytecode for all 30 T-Gremlin steps', function () {
    const g = traversal().withGraph(new Graph());
    const lt = Lifetime.from(1000, 2000);

    const t = g
      .V()
      .atTime(1500)
      .window(1000, 2000)
      .lifetime(1000, 2000)
      .addInterval(3000, 4000)
      .dropInterval(1500, 1800, dropMode.HARD)
      .lifetimeProperty('role', 'engineer', 1000, 2000)
      .temporalOverlaps(lt)
      .temporalBefore(lt)
      .temporalAfter(lt)
      .temporalContains(lt)
      .temporalDuring(lt)
      .temporalEquals(lt)
      .temporalFinishes(lt)
      .temporalFinishedBy(lt)
      .temporalMeets(lt)
      .temporalMetBy(lt)
      .temporalOverlappedBy(lt)
      .temporalStarts(lt)
      .temporalStartedBy(lt)
      .temporalIntersects(lt)
      .temporalDisjoint(lt)
      .continuousPath('knows')
      .sequentialPath(0, 1000, 'knows')
      .pairwiseContinuousPath(true, 'knows')
      .temporalPageRank()
      .with_(TemporalPageRank.alpha, 0.85)
      .with_(TemporalPageRank.bucketMillis, 1000)
      .tbfs(temporalPathTarget.EARLIEST_ARRIVAL, 0)
      .temporalShortestPath(temporalPathTarget.LATEST_DEPARTURE, 100)
      .getLifetime();

    const stepNames = t.getBytecode().stepInstructions.map((s) => s[0]);
    assert.deepStrictEqual(stepNames, [
      'V',
      'atTime',
      'window',
      'lifetime',
      'addInterval',
      'dropInterval',
      'lifetimeProperty',
      'temporalOverlaps',
      'temporalBefore',
      'temporalAfter',
      'temporalContains',
      'temporalDuring',
      'temporalEquals',
      'temporalFinishes',
      'temporalFinishedBy',
      'temporalMeets',
      'temporalMetBy',
      'temporalOverlappedBy',
      'temporalStarts',
      'temporalStartedBy',
      'temporalIntersects',
      'temporalDisjoint',
      'continuousPath',
      'sequentialPath',
      'pairwiseContinuousPath',
      'temporalPageRank',
      'with',
      'with',
      'tbfs',
      'temporalShortestPath',
      'getLifetime',
    ]);

    const anon = __.getStartTime().getEndTime().window(lt);
    assert.deepStrictEqual(
      anon.getBytecode().stepInstructions.map((s) => s[0]),
      ['getStartTime', 'getEndTime', 'window'],
    );
    assert.strictEqual(temporalOptions.quantifier, '~tinkerpop.temporal.quantifier');
    assert.strictEqual(TemporalShortestPath.target, 'gremlin.temporalShortestPathVertexProgram.target');
  });

  it('should round-trip all temporal types, enums, TemporalP, and Bytecode in GraphBinary v1', function () {
    const anySerializer = graphBinary.anySerializer;

    const lt = new Lifetime([
      [100, 200],
      [500, 900],
    ]);
    const iv = new Interval(100, 200);
    const rel = new GeneralizedRelation(2, 2, [0, 2]);
    const irel = new GeneralizedIntervalRelation(
      new GeneralizedRelation(2, 2, [0, 0]),
      new GeneralizedRelation(2, 2, [0, 2]),
    );
    const emptyIrel = GeneralizedIntervalRelation.empty(2, 2);

    const roundTrip = (val) => anySerializer.deserialize(anySerializer.serialize(val)).v;

    assert.ok(roundTrip(lt).equals(lt));
    assert.ok(roundTrip(Lifetime.empty()).equals(Lifetime.empty()));
    assert.ok(roundTrip(Lifetime.unbounded()).equals(Lifetime.unbounded()));
    assert.ok(roundTrip(iv).equals(iv));
    assert.ok(roundTrip(rel).equals(rel));
    assert.ok(roundTrip(irel).equals(irel));
    assert.ok(roundTrip(emptyIrel).equals(emptyIrel));

    assert.strictEqual(roundTrip(lifetimeMode.REPLACE), lifetimeMode.REPLACE);
    assert.strictEqual(roundTrip(dropMode.HARD), dropMode.HARD);
    assert.strictEqual(roundTrip(temporalPathTarget.EARLIEST_ARRIVAL), temporalPathTarget.EARLIEST_ARRIVAL);
    assert.strictEqual(roundTrip(temporal.overlappedBy), temporal.overlappedBy);
    assert.strictEqual(roundTrip(temporalQuantifier.ANY), temporalQuantifier.ANY);

    const predicates = [
      TemporalP.overlaps(lt),
      TemporalP.overlaps(lt).any(),
      TemporalP.overlaps(lt).negate(),
      TemporalP.overlaps(lt).any().negate(),
      TemporalP.intersects(lt).negate(),
      TemporalP.generalized(lt, rel),
      TemporalP.generalized(lt, rel).any(),
      TemporalP.generalized(lt, irel).negate(),
      TemporalP.generalized(lt, emptyIrel).any().negate(),
      TemporalP.between(temporal.precedes, temporal.meets, lt),
    ];

    for (const p of predicates) {
      const dec = roundTrip(p);
      assert.ok(dec instanceof TemporalP);
      assert.strictEqual(dec.toString(), p.toString());
      assert.ok(!dec.toString().includes('[object Object]'), dec.toString());
      assert.strictEqual(dec.biPredicate, p.biPredicate);
      assert.strictEqual(dec.quantifier, p.quantifier);
      assert.strictEqual(dec.negated, p.negated);
      if (p.generalizedRelation) {
        assert.ok(dec.generalizedRelation && dec.generalizedRelation.equals(p.generalizedRelation));
      } else {
        assert.strictEqual(dec.generalizedRelation, null);
      }
      if (p.generalizedIntervalRelation) {
        assert.ok(
          dec.generalizedIntervalRelation && dec.generalizedIntervalRelation.equals(p.generalizedIntervalRelation),
        );
      } else {
        assert.strictEqual(dec.generalizedIntervalRelation, null);
      }
    }

    const g = traversal().withGraph(new Graph());
    const bc = g
      .V()
      .window(lt)
      .has('life', TemporalP.overlaps(lt).any().negate())
      .dropInterval(100, 150, dropMode.HARD)
      .tbfs(0, temporalPathTarget.EARLIEST_ARRIVAL, direction.OUT, 'knows')
      .getBytecode();
    const decBc = roundTrip(bc);
    assert.strictEqual(decBc.stepInstructions.length, bc.stepInstructions.length);
  });

  it('should round-trip all temporal types in GraphSON v3 and v2', function () {
    const lt = new Lifetime([
      [100, 200],
      [500, 900],
    ]);
    const iv = new Interval(100, 200);
    const rel = new GeneralizedRelation(2, 2, [0, 2]);
    const irel = new GeneralizedIntervalRelation(
      new GeneralizedRelation(2, 2, [0, 0]),
      new GeneralizedRelation(2, 2, [0, 2]),
    );
    const emptyIrel = GeneralizedIntervalRelation.empty(2, 2);

    const pairs = [
      [new GraphSON3Writer(), new GraphSON3Reader()],
      [new GraphSON2Writer(), new GraphSON2Reader()],
    ];

    for (const [writer, reader] of pairs) {
      const rt = (v) => reader.read(JSON.parse(writer.write(v)));
      assert.ok(rt(lt).equals(lt));
      assert.ok(rt(Lifetime.empty()).equals(Lifetime.empty()));
      assert.ok(rt(iv).equals(iv));
      assert.ok(rt(rel).equals(rel));
      assert.ok(rt(irel).equals(irel));
      assert.ok(rt(emptyIrel).equals(emptyIrel));
      assert.strictEqual(rt(lifetimeMode.ADD), lifetimeMode.ADD);
      assert.strictEqual(rt(dropMode.HARD), dropMode.HARD);
      assert.strictEqual(rt(temporalPathTarget.EARLIEST_ARRIVAL), temporalPathTarget.EARLIEST_ARRIVAL);
      assert.strictEqual(rt(temporal.overlappedBy), temporal.overlappedBy);
      assert.strictEqual(rt(temporalQuantifier.ANY), temporalQuantifier.ANY);
      assert.strictEqual(rt(1000n), 1000);

      const tp = TemporalP.generalized(lt, irel).any().negate();
      const decTp = rt(tp);
      assert.ok(decTp instanceof TemporalP);
      assert.strictEqual(decTp.toString(), tp.toString());
      assert.ok(decTp.generalizedIntervalRelation && decTp.generalizedIntervalRelation.equals(irel));
      assert.strictEqual(decTp.quantifier, temporalQuantifier.ANY);
      assert.strictEqual(decTp.negated, true);
    }

    // Verify exact Java GraphSON type tag for Quantifier
    const adapted = new GraphSON3Writer().adaptObject(temporalQuantifier.ANY);
    assert.deepStrictEqual(adapted, { '@type': 'g:Quantifier', '@value': 'ANY' });
  });

  it('should translate temporal traversals to valid Gremlin-Groovy via Translator', function () {
    const g = traversal().withGraph(new Graph());
    const lt = Lifetime.from(1000, 2000);
    const t = g
      .V()
      .window(lt)
      .where(__.getLifetime().is(TemporalP.overlaps(lt).negate()))
      .dropInterval(1000, 1500, dropMode.HARD)
      .with_(temporalOptions.quantifier, temporalQuantifier.ANY);
    const script = new gremlin.process.Translator('g').translate(t.getBytecode());
    assert.ok(script.includes("window(Lifetime.fromTemporalIntervals('1000:2000'))"), script);
    assert.ok(script.includes("TemporalP.overlaps(Lifetime.fromTemporalIntervals('1000:2000')).negate()"), script);
    assert.ok(script.includes('DropMode.HARD'), script);
    assert.ok(script.includes('Temporal.Quantifier.ANY'), script);

    const unboundedScript = new gremlin.process.Translator('g').translate(
      g.V().window(Lifetime.unbounded()).atTime(1500n).getBytecode(),
    );
    assert.ok(unboundedScript.includes('window(Lifetime.UNBOUNDED)'), unboundedScript);
    assert.ok(unboundedScript.includes('atTime(1500)'), unboundedScript);

    // Composed TemporalP via .and(), .or(), and P.not()
    const lt2 = Lifetime.from(3000, 4000);
    const composed = g
      .V()
      .has('temporalIntervals', TemporalP.overlaps(lt).and(TemporalP.precedes(lt2)))
      .has('temporalIntervals', gremlin.process.P.not(TemporalP.meets(lt2)));
    const composedScript = new gremlin.process.Translator('g').translate(composed.getBytecode());
    assert.ok(
      composedScript.includes(
        "TemporalP.overlaps(Lifetime.fromTemporalIntervals('1000:2000')).and(TemporalP.precedes(Lifetime.fromTemporalIntervals('3000:4000')))",
      ),
      composedScript,
    );
    assert.ok(
      composedScript.includes("not(TemporalP.meets(Lifetime.fromTemporalIntervals('3000:4000')))"),
      composedScript,
    );
  });

  it('should enforce lattice ordering, strict integer parsing, and signed 64-bit bounds', function () {
    assert.throws(() => TemporalP.between(temporal.succeeds, temporal.precedes, Lifetime.from(100, 200)), /lattice/);
    assert.throws(
      () =>
        new GeneralizedIntervalRelation(
          new GeneralizedRelation(2, 2, [2, 4]),
          new GeneralizedRelation(2, 2, [0, 2]),
        ),
      /lattice/,
    );
    assert.throws(() => Interval.fromGraphSONString(':100'), /Invalid integer/);
    assert.throws(() => Interval.fromGraphSONString('100:'), /Invalid integer/);
    assert.throws(() => Lifetime.fromTemporalIntervals(':100'), /Invalid integer/);
    assert.throws(() => GeneralizedRelation.fromGraphSONString('2,2:0,2abc'), /Invalid integer/);
    assert.throws(() => new Interval(-9223372036854775809n, 100n), RangeError);
    assert.throws(() => new Interval(0n, 9223372036854775808n), RangeError);

    // 2-element array unpacking in TemporalP.generalized
    const lt = Lifetime.from(100, 200);
    const rel = new GeneralizedRelation(2, 2, [0, 2]);
    const unpacked = TemporalP.generalized([lt, rel]);
    assert.ok(unpacked.value.equals(lt));
    assert.ok(unpacked.generalizedRelation.equals(rel));
  });
});
