/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package gremlingo

import (
	"bytes"
	"reflect"
	"testing"

	"golang.org/x/text/language"
)

func TestTemporalLifetimeNormalization(t *testing.T) {
	lt, err := NewLifetime(
		Interval{StartMillis: 300, EndMillis: 400},
		Interval{StartMillis: 100, EndMillis: 200},
		Interval{StartMillis: 200, EndMillis: 250},
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := lt.ToTemporalIntervalsString(); got != "100:250,300:400" {
		t.Fatalf("expected 100:250,300:400, got %s", got)
	}

	// Half-open [100, 200) must NOT merge with point [200, 200]
	halfOpenAndPoint, err := NewLifetime(
		Interval{StartMillis: 100, EndMillis: 200},
		Interval{StartMillis: 200, EndMillis: 200},
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got := halfOpenAndPoint.ToTemporalIntervalsString(); got != "100:200,200:200" {
		t.Fatalf("expected 100:200,200:200, got %s", got)
	}

	if !EmptyLifetime().IsEmpty() {
		t.Fatalf("expected EmptyLifetime().IsEmpty() == true")
	}
	if !UnboundedLifetime().IsUnbounded() {
		t.Fatalf("expected UnboundedLifetime().IsUnbounded() == true")
	}

	parsed, err := LifetimeFromTemporalIntervals(" 300:400, 100:200 , 200:250 ")
	if err != nil {
		t.Fatalf("unexpected parse error: %v", err)
	}
	if got := parsed.ToTemporalIntervalsString(); got != "100:250,300:400" {
		t.Fatalf("expected 100:250,300:400 from parsed intervals, got %s", got)
	}
	if emptyParsed, err := LifetimeFromTemporalIntervals(""); err != nil || !emptyParsed.IsEmpty() {
		t.Fatalf("expected empty lifetime for empty string, got %v, err=%v", emptyParsed, err)
	}

	genOneArg := TemporalP.Generalized(lt)
	if genOneArg.BiPredicate != Temporal.Generalized || genOneArg.Value != lt || genOneArg.GeneralizedRelation != nil {
		t.Fatalf("unexpected 1-arg Generalized predicate: %+v", genOneArg)
	}
}

func TestTemporalDSLBytecode(t *testing.T) {
	g := NewDefaultGraphTraversalSource()
	lt, _ := LifetimeFrom(1000, 2000)

	tr := g.V().
		AtTime(int64(1500)).
		Window(int64(1000), int64(2000)).
		Lifetime(int64(1000), int64(2000)).
		AddInterval(int64(3000), int64(4000)).
		DropInterval(int64(1500), int64(1800), DropMode.Hard).
		LifetimeProperty("role", "engineer", int64(1000), int64(2000)).
		TemporalOverlaps(lt).
		TemporalBefore(lt).
		TemporalAfter(lt).
		TemporalContains(lt).
		TemporalDuring(lt).
		TemporalEquals(lt).
		TemporalFinishes(lt).
		TemporalFinishedBy(lt).
		TemporalMeets(lt).
		TemporalMetBy(lt).
		TemporalOverlappedBy(lt).
		TemporalStarts(lt).
		TemporalStartedBy(lt).
		TemporalIntersects(lt).
		TemporalDisjoint(lt).
		ContinuousPath("knows").
		SequentialPath(int64(0), int64(1000), "knows").
		PairwiseContinuousPath(true, "knows").
		TemporalPageRank().
		With(TemporalPageRank.Alpha, 0.85).
		With(TemporalPageRank.BucketMillis, int64(1000)).
		Tbfs(TemporalPathTarget.EarliestArrival, int64(0)).
		TemporalShortestPath(TemporalPathTarget.LatestDeparture, int64(100)).
		GetLifetime()

	expected := []string{
		"V", "atTime", "window", "lifetime", "addInterval", "dropInterval", "lifetimeProperty",
		"temporalOverlaps", "temporalBefore", "temporalAfter", "temporalContains", "temporalDuring",
		"temporalEquals", "temporalFinishes", "temporalFinishedBy", "temporalMeets", "temporalMetBy",
		"temporalOverlappedBy", "temporalStarts", "temporalStartedBy", "temporalIntersects", "temporalDisjoint",
		"continuousPath", "sequentialPath", "pairwiseContinuousPath", "temporalPageRank", "with", "with",
		"tbfs", "temporalShortestPath", "getLifetime",
	}
	if len(tr.Bytecode.stepInstructions) != len(expected) {
		t.Fatalf("expected %d steps, got %d", len(expected), len(tr.Bytecode.stepInstructions))
	}
	for i, name := range expected {
		if tr.Bytecode.stepInstructions[i].operator != name {
			t.Fatalf("step %d: expected %s, got %s", i, name, tr.Bytecode.stepInstructions[i].operator)
		}
	}

	anon := T__.GetStartTime().GetEndTime().Window(lt)
	if len(anon.Bytecode.stepInstructions) != 3 || anon.Bytecode.stepInstructions[0].operator != "getStartTime" {
		t.Fatalf("unexpected anonymous temporal traversal: %+v", anon.Bytecode.stepInstructions)
	}
}

func TestTemporalGraphBinaryRoundTrip(t *testing.T) {
	ser := &graphBinaryTypeSerializer{logHandler: newLogHandler(&defaultLogger{}, Error, language.English)}

	lt, _ := NewLifetime(Interval{StartMillis: 100, EndMillis: 200}, Interval{StartMillis: 500, EndMillis: 900})
	iv, _ := NewInterval(100, 200)
	rel, _ := NewGeneralizedRelation(2, 2, 0, 2)
	lRel, _ := NewGeneralizedRelation(2, 2, 0, 0)
	uRel, _ := NewGeneralizedRelation(2, 2, 0, 2)
	irel, _ := NewGeneralizedIntervalRelation(lRel, uRel)
	emptyIrel, _ := EmptyGeneralizedIntervalRelation(2, 2)
	betweenP, _ := TemporalP.Between(Temporal.Precedes, Temporal.Meets, lt)

	samples := []interface{}{
		lt,
		EmptyLifetime(),
		UnboundedLifetime(),
		iv,
		rel,
		irel,
		emptyIrel,
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
		TemporalP.Intersects(lt).Negate(),
		TemporalP.Generalized(lt, rel),
		TemporalP.Generalized(lt, rel).Any(),
		TemporalP.Generalized(lt, irel).Negate(),
		TemporalP.Generalized(lt, emptyIrel).Any().Negate(),
		betweenP,
	}

	for idx, sample := range samples {
		buf := bytes.Buffer{}
		if _, err := ser.write(sample, &buf); err != nil {
			t.Fatalf("sample %d (%T): write failed: %v", idx, sample, err)
		}
		raw := buf.Bytes()
		pos := 0
		decoded, err := readFullyQualifiedNullable(&raw, &pos, true)
		if err != nil {
			t.Fatalf("sample %d (%T): read failed: %v", idx, sample, err)
		}
		if pos != len(raw) {
			t.Fatalf("sample %d (%T): did not consume all bytes (%d != %d)", idx, sample, pos, len(raw))
		}
		if !reflect.DeepEqual(decoded, sample) {
			t.Fatalf("sample %d (%T): round-trip mismatch:\ngot:  %#v\nwant: %#v", idx, sample, decoded, sample)
		}
	}

	// Also verify Bytecode containing temporal steps + TemporalP + DropMode + TemporalPathTarget serializes cleanly
	g := NewDefaultGraphTraversalSource()
	bc := g.V().
		AtTime(int64(1700000000000)).
		Window(lt).
		Has("life", TemporalP.Overlaps(lt).Any().Negate().And(TemporalP.NotAnyGeneralized(lt, rel))).
		DropInterval(int64(100), int64(150), DropMode.Hard).
		Tbfs(int64(0), TemporalPathTarget.EarliestArrival, Direction.Out, "knows").
		Bytecode
	buf := bytes.Buffer{}
	if _, err := ser.write(bc, &buf); err != nil {
		t.Fatalf("bytecode write failed: %v", err)
	}
	if buf.Len() == 0 {
		t.Fatalf("expected non-empty serialized bytecode")
	}

	// Verify typed-nil pointers return an error rather than panicking
	var nilLt *Lifetime
	nilBuf := bytes.Buffer{}
	if _, err := ser.write(nilLt, &nilBuf); err == nil {
		t.Fatalf("expected error for typed-nil *Lifetime")
	}

	// Verify malformed non-empty GeneralizedIntervalRelation / GeneralizedRelation struct literals return an error rather than panicking
	badGir := &GeneralizedIntervalRelation{P: 2, Q: 2, Empty: false}
	badBuf := bytes.Buffer{}
	if _, err := ser.write(badGir, &badBuf); err == nil {
		t.Fatalf("expected error for non-empty GeneralizedIntervalRelation with nil bounds")
	}
	badGr := &GeneralizedRelation{P: 2, Q: 2, Zones: []int32{0}}
	badGrBuf := bytes.Buffer{}
	if _, err := ser.write(badGr, &badGrBuf); err == nil {
		t.Fatalf("expected error for GeneralizedRelation with mismatched zone count")
	}

	// Verify lattice order validation (lower <= upper)
	if _, err := TemporalP.Between(Temporal.Succeeds, Temporal.Precedes, lt); err == nil {
		t.Fatalf("expected lattice order error when lower > upper in TemporalP.Between")
	}
	if _, err := NewGeneralizedIntervalRelation(uRel, lRel); err == nil {
		t.Fatalf("expected lattice order error when lower > upper in NewGeneralizedIntervalRelation")
	}
	invertedGir := &GeneralizedIntervalRelation{P: 2, Q: 2, Empty: false, Lower: uRel, Upper: lRel}
	invBuf := bytes.Buffer{}
	if _, err := ser.write(invertedGir, &invBuf); err == nil {
		t.Fatalf("expected error writing inverted GeneralizedIntervalRelation")
	}

	// Verify truncated payloads return error instead of panicking
	truncatedLifetime := []byte{byte(lifetimeType), 0x00, 0x00, 0x00, 0x00, 0x05}
	pos := 0
	if _, err := readFullyQualifiedNullable(&truncatedLifetime, &pos, true); err == nil {
		t.Fatalf("expected error on truncated Lifetime payload")
	}
	truncatedInterval := []byte{byte(intervalType), 0x00, 0x00, 0x01, 0x02}
	pos = 0
	if _, err := readFullyQualifiedNullable(&truncatedInterval, &pos, true); err == nil {
		t.Fatalf("expected error on truncated Interval payload")
	}
	truncatedGr := []byte{byte(generalizedRelationType), 0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x02}
	pos = 0
	if _, err := readFullyQualifiedNullable(&truncatedGr, &pos, true); err == nil {
		t.Fatalf("expected error on truncated GeneralizedRelation payload")
	}

	// Verify invalid enum string returns error
	badDropMode := []byte{byte(dropModeType), 0x00, byte(stringType), 0x00, 0x00, 0x00, 0x00, 0x07, 'I', 'N', 'V', 'A', 'L', 'I', 'D'}
	pos = 0
	if _, err := readFullyQualifiedNullable(&badDropMode, &pos, true); err == nil {
		t.Fatalf("expected error on invalid DropMode wire string")
	}

	// Verify Negate() with default/empty Quantifier folds Intersects -> Disjoint
	rawIntersects := &TemporalPredicate{BiPredicate: Temporal.Intersects, Value: lt}
	if neg := rawIntersects.Negate(); neg.BiPredicate != Temporal.Disjoint || neg.Negated {
		t.Fatalf("expected Intersects with empty quantifier to negate to Disjoint, got %+v", neg)
	}
}

func TestTemporalGroovyTranslator(t *testing.T) {
	g := NewDefaultGraphTraversalSource()
	lt, _ := LifetimeFrom(1000, 2000)
	rel, _ := NewGeneralizedRelation(2, 2, 0, 2)
	tr := g.V().
		Window(lt).
		Window(UnboundedLifetime()).
		Where(T__.GetLifetime().Is(TemporalP.Overlaps(lt).Any().Negate())).
		Has("temporalIntervals", TemporalP.Generalized(lt, rel)).
		DropInterval(int64(1000), int64(1500), DropMode.Hard).
		With(TemporalOptions.Quantifier, TemporalQuantifier.Any)

	translator := NewTranslator("g")
	script, err := translator.Translate(tr.Bytecode)
	if err != nil {
		t.Fatalf("Translate failed: %v", err)
	}
	expectedSubstrings := []string{
		"window(Lifetime.fromTemporalIntervals('1000:2000'))",
		"window(Lifetime.UNBOUNDED)",
		"TemporalP.overlaps(Lifetime.fromTemporalIntervals('1000:2000')).any().negate()",
		"TemporalP.generalized(Lifetime.fromTemporalIntervals('1000:2000'),new GeneralizedRelation(2,2,0,2))",
		"DropMode.HARD",
		"Temporal.Quantifier.ANY",
	}
	for _, sub := range expectedSubstrings {
		if !bytes.Contains([]byte(script), []byte(sub)) {
			t.Fatalf("expected translated script %q to contain %q", script, sub)
		}
	}
}
