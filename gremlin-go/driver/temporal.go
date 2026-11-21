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
	"encoding/binary"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
)

// Temporal enum types matching Java T-TinkerPop enums.

type lifetimeMode string

type lifetimeModes struct {
	Replace lifetimeMode
	Add     lifetimeMode
	Drop    lifetimeMode
}

// LifetimeMode controls how a lifetime step modifies an element's intervals.
var LifetimeMode = lifetimeModes{
	Replace: "REPLACE",
	Add:     "ADD",
	Drop:    "DROP",
}

type dropMode string

type dropModes struct {
	Soft dropMode
	Hard dropMode
}

// DropMode controls whether dropping the final interval retains an empty element (SOFT) or removes it (HARD).
var DropMode = dropModes{
	Soft: "SOFT",
	Hard: "HARD",
}

type temporalPathTarget string

type temporalPathTargets struct {
	EarliestArrival   temporalPathTarget
	EarliestDeparture temporalPathTarget
	LatestDeparture   temporalPathTarget
	LatestArrival     temporalPathTarget
}

// TemporalPathTarget selects the optimization criterion for TBFS and TemporalShortestPath.
var TemporalPathTarget = temporalPathTargets{
	EarliestArrival:   "EARLIEST_ARRIVAL",
	EarliestDeparture: "EARLIEST_DEPARTURE",
	LatestDeparture:   "LATEST_DEPARTURE",
	LatestArrival:     "LATEST_ARRIVAL",
}

type temporal string

type temporals struct {
	Intersects   temporal
	Disjoint     temporal
	Overlaps     temporal
	Precedes     temporal
	Succeeds     temporal
	OverlappedBy temporal
	Meets        temporal
	MetBy        temporal
	During       temporal
	Contains     temporal
	Eq           temporal
	Starts       temporal
	StartedBy    temporal
	Finishes     temporal
	FinishedBy   temporal
	Generalized  temporal
}

// Temporal defines Allen's 13 interval primitives, set intersection/disjointness, and generalized relations.
var Temporal = temporals{
	Intersects:   "intersects",
	Disjoint:     "disjoint",
	Overlaps:     "overlaps",
	Precedes:     "precedes",
	Succeeds:     "succeeds",
	OverlappedBy: "overlappedBy",
	Meets:        "meets",
	MetBy:        "metBy",
	During:       "during",
	Contains:     "contains",
	Eq:           "eq",
	Starts:       "starts",
	StartedBy:    "startedBy",
	Finishes:     "finishes",
	FinishedBy:   "finishedBy",
	Generalized:  "generalized",
}

type temporalQuantifier string

type temporalQuantifiers struct {
	All temporalQuantifier
	Any temporalQuantifier
}

// TemporalQuantifier selects holistic (ALL) vs existential sub-interval (ANY) evaluation semantics.
var TemporalQuantifier = temporalQuantifiers{
	All: "ALL",
	Any: "ANY",
}

// Temporal option constants for GraphTraversal.With(...).

type temporalPageRankOptions struct {
	Alpha        string
	Beta         string
	PropertyName string
	Normalize    string
	BucketMillis string
}

var TemporalPageRank = temporalPageRankOptions{
	Alpha:        "~tinkerpop.temporalPageRank.alpha",
	Beta:         "~tinkerpop.temporalPageRank.beta",
	PropertyName: "~tinkerpop.temporalPageRank.propertyName",
	Normalize:    "~tinkerpop.temporalPageRank.normalize",
	BucketMillis: "~tinkerpop.temporalPageRank.bucketMillis",
}

type temporalShortestPathOptions struct {
	Target             string
	InitialTime        string
	TargetVertexFilter string
	EdgeTraversal      string
	MaxDistance        string
	IncludeEdges       string
}

var TemporalShortestPath = temporalShortestPathOptions{
	Target:             "gremlin.temporalShortestPathVertexProgram.target",
	InitialTime:        "gremlin.temporalShortestPathVertexProgram.initialTime",
	TargetVertexFilter: "gremlin.temporalShortestPathVertexProgram.targetVertexFilter",
	EdgeTraversal:      "gremlin.temporalShortestPathVertexProgram.edgeTraversal",
	MaxDistance:        "gremlin.temporalShortestPathVertexProgram.maxDistance",
	IncludeEdges:       "gremlin.temporalShortestPathVertexProgram.includeEdges",
}

type temporalOptionsStruct struct {
	Quantifier string
}

var TemporalOptions = temporalOptionsStruct{
	Quantifier: "~tinkerpop.temporal.quantifier",
}

// Temporal data structures.

const (
	LifetimeMinMillis int64 = math.MinInt64
	LifetimeMaxMillis int64 = math.MaxInt64
)

// Interval represents a single temporal interval [StartMillis, EndMillis) (or point [t, t]).
type Interval struct {
	StartMillis int64
	EndMillis   int64
}

func NewInterval(startMillis, endMillis int64) (Interval, error) {
	if endMillis < startMillis {
		return Interval{}, fmt.Errorf("end time (%d) must not be before start time (%d)", endMillis, startMillis)
	}
	return Interval{StartMillis: startMillis, EndMillis: endMillis}, nil
}

func canMergeInterval(leftStart, leftEnd, rightStart, rightEnd int64) bool {
	if leftEnd < rightStart {
		return false
	}
	leftIsHalfOpen := leftStart != leftEnd
	rightIsPoint := rightStart == rightEnd
	return !(leftIsHalfOpen && rightIsPoint && leftEnd == rightStart)
}

// Lifetime represents a normalized, sorted, non-overlapping sequence of temporal intervals.
type Lifetime struct {
	Intervals []Interval
}

func NewLifetime(intervals ...Interval) (*Lifetime, error) {
	if len(intervals) == 0 {
		return &Lifetime{Intervals: []Interval{}}, nil
	}
	cp := make([]Interval, len(intervals))
	for i, iv := range intervals {
		if iv.EndMillis < iv.StartMillis {
			return nil, fmt.Errorf("end time (%d) must not be before start time (%d)", iv.EndMillis, iv.StartMillis)
		}
		cp[i] = iv
	}
	sort.Slice(cp, func(i, j int) bool {
		if cp[i].StartMillis != cp[j].StartMillis {
			return cp[i].StartMillis < cp[j].StartMillis
		}
		return cp[i].EndMillis < cp[j].EndMillis
	})
	merged := make([]Interval, 0, len(cp))
	cur := cp[0]
	for i := 1; i < len(cp); i++ {
		next := cp[i]
		if canMergeInterval(cur.StartMillis, cur.EndMillis, next.StartMillis, next.EndMillis) {
			if next.EndMillis > cur.EndMillis {
				cur.EndMillis = next.EndMillis
			}
			if len(merged) > 0 {
				prev := merged[len(merged)-1]
				if canMergeInterval(prev.StartMillis, prev.EndMillis, cur.StartMillis, cur.EndMillis) {
					merged = merged[:len(merged)-1]
					cur.StartMillis = prev.StartMillis
					if prev.EndMillis > cur.EndMillis {
						cur.EndMillis = prev.EndMillis
					}
				}
			}
		} else {
			merged = append(merged, cur)
			cur = next
		}
	}
	merged = append(merged, cur)
	return &Lifetime{Intervals: merged}, nil
}

func EmptyLifetime() *Lifetime {
	return &Lifetime{Intervals: []Interval{}}
}

func UnboundedLifetime() *Lifetime {
	return &Lifetime{Intervals: []Interval{{StartMillis: LifetimeMinMillis, EndMillis: LifetimeMaxMillis}}}
}

func LifetimeFrom(startMillis, endMillis int64) (*Lifetime, error) {
	return NewLifetime(Interval{StartMillis: startMillis, EndMillis: endMillis})
}

func (l *Lifetime) IsEmpty() bool {
	return l == nil || len(l.Intervals) == 0
}

func (l *Lifetime) IsUnbounded() bool {
	return l != nil && len(l.Intervals) == 1 && l.Intervals[0].StartMillis <= LifetimeMinMillis && l.Intervals[0].EndMillis >= LifetimeMaxMillis
}

func (l *Lifetime) ToTemporalIntervalsString() string {
	if l == nil || len(l.Intervals) == 0 {
		return ""
	}
	parts := make([]string, len(l.Intervals))
	for i, iv := range l.Intervals {
		parts[i] = strconv.FormatInt(iv.StartMillis, 10) + ":" + strconv.FormatInt(iv.EndMillis, 10)
	}
	return strings.Join(parts, ",")
}

func LifetimeFromTemporalIntervals(text string) (*Lifetime, error) {
	trimmed := strings.TrimSpace(text)
	if trimmed == "" {
		return EmptyLifetime(), nil
	}
	parts := strings.Split(trimmed, ",")
	ivs := make([]Interval, 0, len(parts))
	for _, raw := range parts {
		part := strings.TrimSpace(raw)
		if part == "" {
			continue
		}
		bounds := strings.Split(part, ":")
		if len(bounds) != 2 {
			return nil, fmt.Errorf("invalid temporalIntervals component: %q", part)
		}
		s, err := strconv.ParseInt(strings.TrimSpace(bounds[0]), 10, 64)
		if err != nil {
			return nil, err
		}
		e, err := strconv.ParseInt(strings.TrimSpace(bounds[1]), 10, 64)
		if err != nil {
			return nil, err
		}
		iv, err := NewInterval(s, e)
		if err != nil {
			return nil, err
		}
		ivs = append(ivs, iv)
	}
	return NewLifetime(ivs...)
}

// GeneralizedRelation represents a Ligozat (p, q)-relation in Pi(p, q).
type GeneralizedRelation struct {
	P     int32
	Q     int32
	Zones []int32
}

var allenZonesMap = map[temporal][]int32{
	Temporal.Precedes:     {0, 0},
	Temporal.Meets:        {0, 1},
	Temporal.Overlaps:     {0, 2},
	Temporal.FinishedBy:   {0, 3},
	Temporal.Contains:     {0, 4},
	Temporal.Starts:       {1, 2},
	Temporal.Eq:           {1, 3},
	Temporal.StartedBy:    {1, 4},
	Temporal.During:       {2, 2},
	Temporal.Finishes:     {2, 3},
	Temporal.OverlappedBy: {2, 4},
	Temporal.MetBy:        {3, 4},
	Temporal.Succeeds:     {4, 4},
}

func NewGeneralizedRelation(p, q int32, zones ...int32) (*GeneralizedRelation, error) {
	if p < 1 || q < 1 {
		return nil, fmt.Errorf("arities p and q must be >= 1, got (%d, %d)", p, q)
	}
	if int32(len(zones)) != p {
		return nil, fmt.Errorf("expected %d zone values, got %d", p, len(zones))
	}
	maxZone := 2 * q
	cp := make([]int32, p)
	for i, z := range zones {
		if z < 0 || z > maxZone {
			return nil, fmt.Errorf("zone index out of bounds [0, %d]: %d", maxZone, z)
		}
		if i > 0 {
			prev := cp[i-1]
			if z < prev {
				return nil, fmt.Errorf("zones must be non-decreasing")
			}
			if (z&1) == 1 && z == prev {
				return nil, fmt.Errorf("odd zone %d may occur at most once", z)
			}
		}
		cp[i] = z
	}
	return &GeneralizedRelation{P: p, Q: q, Zones: cp}, nil
}

func GeneralizedRelationFromAllen(op temporal) (*GeneralizedRelation, error) {
	zones, ok := allenZonesMap[op]
	if !ok {
		return nil, fmt.Errorf("unsupported Allen Temporal operator: %s", op)
	}
	return NewGeneralizedRelation(2, 2, zones...)
}

// GeneralizedIntervalRelation represents a Ligozat convex lattice interval [Lower, Upper] in Pi(p, q).
type GeneralizedIntervalRelation struct {
	P     int32
	Q     int32
	Empty bool
	Lower *GeneralizedRelation
	Upper *GeneralizedRelation
}

func NewGeneralizedIntervalRelation(lower, upper *GeneralizedRelation) (*GeneralizedIntervalRelation, error) {
	if lower == nil || upper == nil {
		return nil, fmt.Errorf("lower and upper cannot be nil")
	}
	if lower.P != upper.P || lower.Q != upper.Q {
		return nil, fmt.Errorf("lower and upper must have matching (p, q) arities")
	}
	if int32(len(lower.Zones)) != lower.P || int32(len(upper.Zones)) != upper.P {
		return nil, fmt.Errorf("lower and upper zone counts (%d, %d) must match arity p=%d", len(lower.Zones), len(upper.Zones), lower.P)
	}
	for i := int32(0); i < lower.P; i++ {
		if lower.Zones[i] > upper.Zones[i] {
			return nil, fmt.Errorf("lower bound must be <= upper bound in lattice order: %v vs %v", lower.Zones, upper.Zones)
		}
	}
	return &GeneralizedIntervalRelation{P: lower.P, Q: lower.Q, Empty: false, Lower: lower, Upper: upper}, nil
}

func EmptyGeneralizedIntervalRelation(p, q int32) (*GeneralizedIntervalRelation, error) {
	if p < 1 || q < 1 {
		return nil, fmt.Errorf("arities p and q must be >= 1")
	}
	return &GeneralizedIntervalRelation{P: p, Q: q, Empty: true}, nil
}

// TemporalPredicate represents a temporal predicate (TemporalP).
type TemporalPredicate struct {
	BiPredicate                 temporal
	Value                       interface{}
	GeneralizedRelation         *GeneralizedRelation
	GeneralizedIntervalRelation *GeneralizedIntervalRelation
	Quantifier                  temporalQuantifier
	Negated                     bool
}

func newTemporalP(op temporal, value interface{}) *TemporalPredicate {
	return &TemporalPredicate{
		BiPredicate: op,
		Value:       value,
		Quantifier:  TemporalQuantifier.All,
		Negated:     false,
	}
}

// Any sets existential sub-interval semantics (TemporalQuantifier.Any) on this TemporalPredicate.
func (tp *TemporalPredicate) Any() *TemporalPredicate {
	tp.Quantifier = TemporalQuantifier.Any
	return tp
}

// All sets holistic whole-lifetime semantics (TemporalQuantifier.All) on this TemporalPredicate.
func (tp *TemporalPredicate) All() *TemporalPredicate {
	tp.Quantifier = TemporalQuantifier.All
	return tp
}

// Negate returns a negated copy of this TemporalPredicate, matching Java's TemporalP.negate().
func (tp *TemporalPredicate) Negate() *TemporalPredicate {
	if !tp.Negated && (tp.Quantifier == "" || tp.Quantifier == TemporalQuantifier.All) {
		if tp.BiPredicate == Temporal.Intersects {
			return newTemporalP(Temporal.Disjoint, tp.Value)
		}
		if tp.BiPredicate == Temporal.Disjoint {
			return newTemporalP(Temporal.Intersects, tp.Value)
		}
	}
	cp := *tp
	if cp.Quantifier == "" {
		cp.Quantifier = TemporalQuantifier.All
	}
	cp.Negated = !tp.Negated
	return &cp
}

// And combines this TemporalPredicate with another predicate using logical AND.
func (tp *TemporalPredicate) And(other interface{}) Predicate {
	return &p{operator: "and", values: []interface{}{tp, other}}
}

// Or combines this TemporalPredicate with another predicate using logical OR.
func (tp *TemporalPredicate) Or(other interface{}) Predicate {
	return &p{operator: "or", values: []interface{}{tp, other}}
}

func (tp *TemporalPredicate) toWireParts() (string, []interface{}) {
	isAny := tp.Quantifier == TemporalQuantifier.Any
	isNeg := tp.Negated
	if tp.BiPredicate == Temporal.Generalized {
		var predName string
		if isNeg {
			if isAny {
				predName = "notAnyGeneralized"
			} else {
				predName = "notGeneralized"
			}
		} else {
			if isAny {
				predName = "anyGeneralized"
			} else {
				predName = "generalized"
			}
		}
		if tp.GeneralizedRelation != nil {
			return predName, []interface{}{tp.Value, tp.GeneralizedRelation}
		}
		if tp.GeneralizedIntervalRelation != nil {
			return predName, []interface{}{tp.Value, tp.GeneralizedIntervalRelation}
		}
		return predName, []interface{}{tp.Value}
	}
	if isAny || isNeg {
		var predName string
		if isNeg {
			if isAny {
				predName = "notAny"
			} else {
				predName = "not"
			}
		} else {
			predName = "any"
		}
		return predName, []interface{}{tp.BiPredicate, tp.Value}
	}
	return string(tp.BiPredicate), []interface{}{tp.Value}
}

var validTemporalSet = map[temporal]bool{
	Temporal.Intersects:   true,
	Temporal.Disjoint:     true,
	Temporal.Overlaps:     true,
	Temporal.Precedes:     true,
	Temporal.Succeeds:     true,
	Temporal.OverlappedBy: true,
	Temporal.Meets:        true,
	Temporal.MetBy:        true,
	Temporal.During:       true,
	Temporal.Contains:     true,
	Temporal.Eq:           true,
	Temporal.Starts:       true,
	Temporal.StartedBy:    true,
	Temporal.Finishes:     true,
	Temporal.FinishedBy:   true,
	Temporal.Generalized:  true,
}

func temporalPFromWireParts(predName string, args []interface{}) (*TemporalPredicate, error) {
	if predName == "any" || predName == "not" || predName == "notAny" || predName == "all" {
		if len(args) != 2 {
			return nil, fmt.Errorf("expected 2 arguments for TemporalP.%s, got %d", predName, len(args))
		}
		var op temporal
		switch v := args[0].(type) {
		case temporal:
			op = v
		case string:
			op = temporal(v)
		default:
			return nil, fmt.Errorf("expected temporal operator in TemporalP.%s, got %T", predName, args[0])
		}
		if !validTemporalSet[op] {
			return nil, fmt.Errorf("unknown Temporal operator %q in TemporalP.%s", op, predName)
		}
		tp := newTemporalP(op, args[1])
		if predName == "any" || predName == "notAny" {
			tp = tp.Any()
		}
		if predName == "not" || predName == "notAny" {
			tp = tp.Negate()
		}
		return tp, nil
	}
	if predName == "generalized" || predName == "anyGeneralized" || predName == "notGeneralized" || predName == "notAnyGeneralized" {
		if len(args) < 1 || len(args) > 2 {
			return nil, fmt.Errorf("expected 1 or 2 arguments for TemporalP.%s, got %d", predName, len(args))
		}
		var tp *TemporalPredicate
		if len(args) == 2 {
			switch spec := args[1].(type) {
			case *GeneralizedRelation:
				tp = &TemporalPredicate{BiPredicate: Temporal.Generalized, Value: args[0], GeneralizedRelation: spec, Quantifier: TemporalQuantifier.All}
			case GeneralizedRelation:
				cp := spec
				tp = &TemporalPredicate{BiPredicate: Temporal.Generalized, Value: args[0], GeneralizedRelation: &cp, Quantifier: TemporalQuantifier.All}
			case *GeneralizedIntervalRelation:
				tp = &TemporalPredicate{BiPredicate: Temporal.Generalized, Value: args[0], GeneralizedIntervalRelation: spec, Quantifier: TemporalQuantifier.All}
			case GeneralizedIntervalRelation:
				cp := spec
				tp = &TemporalPredicate{BiPredicate: Temporal.Generalized, Value: args[0], GeneralizedIntervalRelation: &cp, Quantifier: TemporalQuantifier.All}
			default:
				tp = newTemporalP(Temporal.Generalized, args)
			}
		} else {
			tp = newTemporalP(Temporal.Generalized, args[0])
		}
		if predName == "anyGeneralized" || predName == "notAnyGeneralized" {
			tp = tp.Any()
		}
		if predName == "notGeneralized" || predName == "notAnyGeneralized" {
			tp = tp.Negate()
		}
		return tp, nil
	}
	op := temporal(predName)
	if !validTemporalSet[op] {
		return nil, fmt.Errorf("unknown TemporalP predicate %q", predName)
	}
	if len(args) == 0 {
		return nil, fmt.Errorf("TemporalP.%s requires at least 1 argument", predName)
	}
	var val interface{}
	if len(args) == 1 {
		val = args[0]
	} else {
		val = args
	}
	return newTemporalP(op, val), nil
}

type temporalPFactory struct{}

// TemporalP provides static factory methods for building TemporalPredicate instances.
var TemporalP = temporalPFactory{}

func (temporalPFactory) Intersects(v interface{}) *TemporalPredicate   { return newTemporalP(Temporal.Intersects, v) }
func (temporalPFactory) Disjoint(v interface{}) *TemporalPredicate     { return newTemporalP(Temporal.Disjoint, v) }
func (temporalPFactory) Overlaps(v interface{}) *TemporalPredicate     { return newTemporalP(Temporal.Overlaps, v) }
func (temporalPFactory) Precedes(v interface{}) *TemporalPredicate     { return newTemporalP(Temporal.Precedes, v) }
func (temporalPFactory) Succeeds(v interface{}) *TemporalPredicate     { return newTemporalP(Temporal.Succeeds, v) }
func (temporalPFactory) OverlappedBy(v interface{}) *TemporalPredicate { return newTemporalP(Temporal.OverlappedBy, v) }
func (temporalPFactory) Meets(v interface{}) *TemporalPredicate        { return newTemporalP(Temporal.Meets, v) }
func (temporalPFactory) MetBy(v interface{}) *TemporalPredicate        { return newTemporalP(Temporal.MetBy, v) }
func (temporalPFactory) During(v interface{}) *TemporalPredicate       { return newTemporalP(Temporal.During, v) }
func (temporalPFactory) Contains(v interface{}) *TemporalPredicate     { return newTemporalP(Temporal.Contains, v) }
func (temporalPFactory) Eq(v interface{}) *TemporalPredicate           { return newTemporalP(Temporal.Eq, v) }
func (temporalPFactory) Starts(v interface{}) *TemporalPredicate       { return newTemporalP(Temporal.Starts, v) }
func (temporalPFactory) StartedBy(v interface{}) *TemporalPredicate    { return newTemporalP(Temporal.StartedBy, v) }
func (temporalPFactory) Finishes(v interface{}) *TemporalPredicate     { return newTemporalP(Temporal.Finishes, v) }
func (temporalPFactory) FinishedBy(v interface{}) *TemporalPredicate   { return newTemporalP(Temporal.FinishedBy, v) }

func (temporalPFactory) Any(op temporal, v interface{}) *TemporalPredicate {
	return newTemporalP(op, v).Any()
}

func (temporalPFactory) All(op temporal, v interface{}) *TemporalPredicate {
	return newTemporalP(op, v).All()
}

func (temporalPFactory) Not(op temporal, v interface{}) *TemporalPredicate {
	return newTemporalP(op, v).Negate()
}

func (temporalPFactory) NotAny(op temporal, v interface{}) *TemporalPredicate {
	return newTemporalP(op, v).Any().Negate()
}

func (temporalPFactory) Between(lower, upper temporal, v interface{}) (*TemporalPredicate, error) {
	l, err := GeneralizedRelationFromAllen(lower)
	if err != nil {
		return nil, err
	}
	u, err := GeneralizedRelationFromAllen(upper)
	if err != nil {
		return nil, err
	}
	ir, err := NewGeneralizedIntervalRelation(l, u)
	if err != nil {
		return nil, err
	}
	return &TemporalPredicate{
		BiPredicate:                 Temporal.Generalized,
		Value:                       v,
		GeneralizedIntervalRelation: ir,
		Quantifier:                  TemporalQuantifier.All,
	}, nil
}

func (temporalPFactory) Generalized(v interface{}, spec ...interface{}) *TemporalPredicate {
	if len(spec) == 0 {
		if pair, ok := v.([]interface{}); ok && len(pair) == 2 {
			switch s := pair[1].(type) {
			case *GeneralizedRelation, GeneralizedRelation, *GeneralizedIntervalRelation, GeneralizedIntervalRelation:
				return TemporalP.Generalized(pair[0], s)
			}
		}
	}
	tp := newTemporalP(Temporal.Generalized, v)
	if len(spec) > 0 && spec[0] != nil {
		switch s := spec[0].(type) {
		case *GeneralizedRelation:
			tp.GeneralizedRelation = s
		case GeneralizedRelation:
			cp := s
			tp.GeneralizedRelation = &cp
		case *GeneralizedIntervalRelation:
			tp.GeneralizedIntervalRelation = s
		case GeneralizedIntervalRelation:
			cp := s
			tp.GeneralizedIntervalRelation = &cp
		default:
			panic(fmt.Sprintf("TemporalP.Generalized: unsupported relation spec type %T", spec[0]))
		}
	}
	return tp
}

func (f temporalPFactory) AnyGeneralized(v interface{}, spec ...interface{}) *TemporalPredicate {
	return f.Generalized(v, spec...).Any()
}

func (f temporalPFactory) NotGeneralized(v interface{}, spec ...interface{}) *TemporalPredicate {
	return f.Generalized(v, spec...).Negate()
}

func (f temporalPFactory) NotAnyGeneralized(v interface{}, spec ...interface{}) *TemporalPredicate {
	return f.Generalized(v, spec...).Any().Negate()
}

// Temporal GraphTraversal steps (30 T-Gremlin steps).

func (g *GraphTraversal) Lifetime(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("lifetime", args...)
	return g
}

func (g *GraphTraversal) AddInterval(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("addInterval", args...)
	return g
}

func (g *GraphTraversal) DropInterval(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("dropInterval", args...)
	return g
}

func (g *GraphTraversal) LifetimeProperty(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("lifetimeProperty", args...)
	return g
}

func (g *GraphTraversal) GetStartTime(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("getStartTime", args...)
	return g
}

func (g *GraphTraversal) GetEndTime(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("getEndTime", args...)
	return g
}

func (g *GraphTraversal) GetLifetime(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("getLifetime", args...)
	return g
}

func (g *GraphTraversal) AtTime(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("atTime", args...)
	return g
}

func (g *GraphTraversal) Window(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("window", args...)
	return g
}

func (g *GraphTraversal) TemporalAfter(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalAfter", args...)
	return g
}

func (g *GraphTraversal) TemporalBefore(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalBefore", args...)
	return g
}

func (g *GraphTraversal) TemporalContains(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalContains", args...)
	return g
}

func (g *GraphTraversal) TemporalDuring(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalDuring", args...)
	return g
}

func (g *GraphTraversal) TemporalEquals(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalEquals", args...)
	return g
}

func (g *GraphTraversal) TemporalFinishes(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalFinishes", args...)
	return g
}

func (g *GraphTraversal) TemporalFinishedBy(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalFinishedBy", args...)
	return g
}

func (g *GraphTraversal) TemporalMeets(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalMeets", args...)
	return g
}

func (g *GraphTraversal) TemporalMetBy(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalMetBy", args...)
	return g
}

func (g *GraphTraversal) TemporalOverlaps(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalOverlaps", args...)
	return g
}

func (g *GraphTraversal) TemporalOverlappedBy(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalOverlappedBy", args...)
	return g
}

func (g *GraphTraversal) TemporalStarts(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalStarts", args...)
	return g
}

func (g *GraphTraversal) TemporalStartedBy(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalStartedBy", args...)
	return g
}

func (g *GraphTraversal) TemporalIntersects(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalIntersects", args...)
	return g
}

func (g *GraphTraversal) TemporalDisjoint(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalDisjoint", args...)
	return g
}

func (g *GraphTraversal) ContinuousPath(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("continuousPath", args...)
	return g
}

func (g *GraphTraversal) SequentialPath(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("sequentialPath", args...)
	return g
}

func (g *GraphTraversal) PairwiseContinuousPath(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("pairwiseContinuousPath", args...)
	return g
}

func (g *GraphTraversal) TemporalPageRank(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalPageRank", args...)
	return g
}

func (g *GraphTraversal) Tbfs(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("tbfs", args...)
	return g
}

func (g *GraphTraversal) TemporalShortestPath(args ...interface{}) *GraphTraversal {
	g.Bytecode.AddStep("temporalShortestPath", args...)
	return g
}

// GraphBinary v1 writers and readers for temporal types (0x30 - 0x39).

func temporalPWriter(value interface{}, buffer *bytes.Buffer, typeSerializer *graphBinaryTypeSerializer) ([]byte, error) {
	var tp *TemporalPredicate
	switch v := value.(type) {
	case *TemporalPredicate:
		if v == nil {
			return nil, fmt.Errorf("TemporalPredicate pointer cannot be nil")
		}
		tp = v
	case TemporalPredicate:
		tp = &v
	default:
		return nil, fmt.Errorf("expected TemporalPredicate, got %T", value)
	}
	predName, args := tp.toWireParts()
	if _, err := typeSerializer.writeValue(predName, buffer, false); err != nil {
		return nil, err
	}
	if err := binary.Write(buffer, binary.BigEndian, int32(len(args))); err != nil {
		return nil, err
	}
	for _, arg := range args {
		if _, err := typeSerializer.write(arg, buffer); err != nil {
			return nil, err
		}
	}
	return buffer.Bytes(), nil
}

func ensureRemainingBytes(data *[]byte, i *int, needed int, context string) error {
	if data == nil || i == nil || needed < 0 || *i < 0 || *i+needed > len(*data) {
		avail := 0
		if data != nil && i != nil && *i >= 0 && *i <= len(*data) {
			avail = len(*data) - *i
		}
		return fmt.Errorf("unexpected EOF in %s: need %d bytes, have %d", context, needed, avail)
	}
	return nil
}

func temporalPReader(data *[]byte, i *int) (interface{}, error) {
	nameObj, err := readUnqualified(data, i, stringType, false)
	if err != nil {
		return nil, err
	}
	predName := nameObj.(string)
	if err := ensureRemainingBytes(data, i, 4, "TemporalP arg count"); err != nil {
		return nil, err
	}
	length := int(readIntSafe(data, i))
	if length < 0 {
		return nil, fmt.Errorf("invalid negative TemporalP argument count: %d", length)
	}
	// Each fully-qualified nullable argument takes at least 2 bytes (type_code + value_flag)
	if length > 0 {
		if err := ensureRemainingBytes(data, i, length*2, "TemporalP arguments"); err != nil {
			return nil, err
		}
	}
	args := make([]interface{}, length)
	for j := 0; j < length; j++ {
		val, err := readFullyQualifiedNullable(data, i, true)
		if err != nil {
			return nil, err
		}
		args[j] = val
	}
	return temporalPFromWireParts(predName, args)
}

func lifetimeWriter(value interface{}, buffer *bytes.Buffer, _ *graphBinaryTypeSerializer) ([]byte, error) {
	var lt *Lifetime
	switch v := value.(type) {
	case *Lifetime:
		if v == nil {
			return nil, fmt.Errorf("Lifetime pointer cannot be nil")
		}
		lt = v
	case Lifetime:
		lt = &v
	default:
		return nil, fmt.Errorf("expected Lifetime, got %T", value)
	}
	for _, iv := range lt.Intervals {
		if iv.EndMillis < iv.StartMillis {
			return nil, fmt.Errorf("invalid Lifetime interval: end time (%d) < start time (%d)", iv.EndMillis, iv.StartMillis)
		}
	}
	if err := binary.Write(buffer, binary.BigEndian, int32(len(lt.Intervals))); err != nil {
		return nil, err
	}
	for _, iv := range lt.Intervals {
		if err := binary.Write(buffer, binary.BigEndian, iv.StartMillis); err != nil {
			return nil, err
		}
		if err := binary.Write(buffer, binary.BigEndian, iv.EndMillis); err != nil {
			return nil, err
		}
	}
	return buffer.Bytes(), nil
}

func lifetimeReader(data *[]byte, i *int) (interface{}, error) {
	if err := ensureRemainingBytes(data, i, 4, "Lifetime count"); err != nil {
		return nil, err
	}
	count := int(readIntSafe(data, i))
	if count < 0 {
		return nil, fmt.Errorf("invalid negative Lifetime interval count: %d", count)
	}
	if count > (len(*data)-*i)/16 {
		return nil, fmt.Errorf("truncated Lifetime payload: count=%d requires %d bytes, have %d", count, count*16, len(*data)-*i)
	}
	ivs := make([]Interval, count)
	for j := 0; j < count; j++ {
		s := readLongSafe(data, i)
		e := readLongSafe(data, i)
		ivs[j] = Interval{StartMillis: s, EndMillis: e}
	}
	return NewLifetime(ivs...)
}

func intervalWriter(value interface{}, buffer *bytes.Buffer, _ *graphBinaryTypeSerializer) ([]byte, error) {
	var iv Interval
	switch v := value.(type) {
	case *Interval:
		if v == nil {
			return nil, fmt.Errorf("Interval pointer cannot be nil")
		}
		iv = *v
	case Interval:
		iv = v
	default:
		return nil, fmt.Errorf("expected Interval, got %T", value)
	}
	if iv.EndMillis < iv.StartMillis {
		return nil, fmt.Errorf("invalid Interval: end time (%d) < start time (%d)", iv.EndMillis, iv.StartMillis)
	}
	if err := binary.Write(buffer, binary.BigEndian, iv.StartMillis); err != nil {
		return nil, err
	}
	if err := binary.Write(buffer, binary.BigEndian, iv.EndMillis); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

func intervalReader(data *[]byte, i *int) (interface{}, error) {
	if err := ensureRemainingBytes(data, i, 16, "Interval"); err != nil {
		return nil, err
	}
	s := readLongSafe(data, i)
	e := readLongSafe(data, i)
	return NewInterval(s, e)
}

func generalizedRelationWriter(value interface{}, buffer *bytes.Buffer, _ *graphBinaryTypeSerializer) ([]byte, error) {
	var gr *GeneralizedRelation
	switch v := value.(type) {
	case *GeneralizedRelation:
		if v == nil {
			return nil, fmt.Errorf("GeneralizedRelation pointer cannot be nil")
		}
		gr = v
	case GeneralizedRelation:
		gr = &v
	default:
		return nil, fmt.Errorf("expected GeneralizedRelation, got %T", value)
	}
	if _, err := NewGeneralizedRelation(gr.P, gr.Q, gr.Zones...); err != nil {
		return nil, err
	}
	if err := binary.Write(buffer, binary.BigEndian, gr.P); err != nil {
		return nil, err
	}
	if err := binary.Write(buffer, binary.BigEndian, gr.Q); err != nil {
		return nil, err
	}
	for _, z := range gr.Zones {
		if err := binary.Write(buffer, binary.BigEndian, z); err != nil {
			return nil, err
		}
	}
	return buffer.Bytes(), nil
}

func generalizedRelationReader(data *[]byte, i *int) (interface{}, error) {
	if err := ensureRemainingBytes(data, i, 8, "GeneralizedRelation header"); err != nil {
		return nil, err
	}
	pVal := readIntSafe(data, i)
	qVal := readIntSafe(data, i)
	if pVal < 1 || qVal < 1 {
		return nil, fmt.Errorf("invalid GeneralizedRelation arities: (%d, %d)", pVal, qVal)
	}
	if int(pVal) > (len(*data)-*i)/4 {
		return nil, fmt.Errorf("truncated GeneralizedRelation payload: p=%d requires %d bytes, have %d", pVal, int(pVal)*4, len(*data)-*i)
	}
	zones := make([]int32, int(pVal))
	for j := 0; j < int(pVal); j++ {
		zones[j] = readIntSafe(data, i)
	}
	return NewGeneralizedRelation(pVal, qVal, zones...)
}

func generalizedIntervalRelationWriter(value interface{}, buffer *bytes.Buffer, _ *graphBinaryTypeSerializer) ([]byte, error) {
	var gir *GeneralizedIntervalRelation
	switch v := value.(type) {
	case *GeneralizedIntervalRelation:
		if v == nil {
			return nil, fmt.Errorf("GeneralizedIntervalRelation pointer cannot be nil")
		}
		gir = v
	case GeneralizedIntervalRelation:
		gir = &v
	default:
		return nil, fmt.Errorf("expected GeneralizedIntervalRelation, got %T", value)
	}
	if gir.P < 1 || gir.Q < 1 {
		return nil, fmt.Errorf("invalid GeneralizedIntervalRelation arities: (%d, %d)", gir.P, gir.Q)
	}
	if !gir.Empty {
		if gir.Lower == nil || gir.Upper == nil {
			return nil, fmt.Errorf("non-empty GeneralizedIntervalRelation must have non-nil Lower and Upper")
		}
		if gir.Lower.P != gir.P || gir.Lower.Q != gir.Q || gir.Upper.P != gir.P || gir.Upper.Q != gir.Q {
			return nil, fmt.Errorf("GeneralizedIntervalRelation Lower/Upper arities do not match (%d, %d)", gir.P, gir.Q)
		}
		if _, err := NewGeneralizedRelation(gir.P, gir.Q, gir.Lower.Zones...); err != nil {
			return nil, err
		}
		if _, err := NewGeneralizedRelation(gir.P, gir.Q, gir.Upper.Zones...); err != nil {
			return nil, err
		}
		if _, err := NewGeneralizedIntervalRelation(gir.Lower, gir.Upper); err != nil {
			return nil, err
		}
	}
	if err := binary.Write(buffer, binary.BigEndian, gir.P); err != nil {
		return nil, err
	}
	if err := binary.Write(buffer, binary.BigEndian, gir.Q); err != nil {
		return nil, err
	}
	if err := binary.Write(buffer, binary.BigEndian, gir.Empty); err != nil {
		return nil, err
	}
	if !gir.Empty {
		for _, z := range gir.Lower.Zones {
			if err := binary.Write(buffer, binary.BigEndian, z); err != nil {
				return nil, err
			}
		}
		for _, z := range gir.Upper.Zones {
			if err := binary.Write(buffer, binary.BigEndian, z); err != nil {
				return nil, err
			}
		}
	}
	return buffer.Bytes(), nil
}

func generalizedIntervalRelationReader(data *[]byte, i *int) (interface{}, error) {
	if err := ensureRemainingBytes(data, i, 9, "GeneralizedIntervalRelation header"); err != nil {
		return nil, err
	}
	pVal := readIntSafe(data, i)
	qVal := readIntSafe(data, i)
	if pVal < 1 || qVal < 1 {
		return nil, fmt.Errorf("invalid GeneralizedIntervalRelation arities: (%d, %d)", pVal, qVal)
	}
	empty := readByteSafe(data, i) != 0
	if empty {
		return EmptyGeneralizedIntervalRelation(pVal, qVal)
	}
	if int(pVal)*2 > (len(*data)-*i)/4 {
		return nil, fmt.Errorf("truncated GeneralizedIntervalRelation payload: p=%d requires %d bytes, have %d", pVal, int(pVal)*8, len(*data)-*i)
	}
	lowerZones := make([]int32, int(pVal))
	for j := 0; j < int(pVal); j++ {
		lowerZones[j] = readIntSafe(data, i)
	}
	upperZones := make([]int32, int(pVal))
	for j := 0; j < int(pVal); j++ {
		upperZones[j] = readIntSafe(data, i)
	}
	lower, err := NewGeneralizedRelation(pVal, qVal, lowerZones...)
	if err != nil {
		return nil, err
	}
	upper, err := NewGeneralizedRelation(pVal, qVal, upperZones...)
	if err != nil {
		return nil, err
	}
	return NewGeneralizedIntervalRelation(lower, upper)
}

func lifetimeModeReader(data *[]byte, i *int) (interface{}, error) {
	s, err := enumReader(data, i)
	if err != nil {
		return nil, err
	}
	val := lifetimeMode(s.(string))
	switch val {
	case LifetimeMode.Replace, LifetimeMode.Add, LifetimeMode.Drop:
		return val, nil
	default:
		return nil, fmt.Errorf("unknown LifetimeMode value %q", val)
	}
}

func dropModeReader(data *[]byte, i *int) (interface{}, error) {
	s, err := enumReader(data, i)
	if err != nil {
		return nil, err
	}
	val := dropMode(s.(string))
	switch val {
	case DropMode.Soft, DropMode.Hard:
		return val, nil
	default:
		return nil, fmt.Errorf("unknown DropMode value %q", val)
	}
}

func temporalPathTargetReader(data *[]byte, i *int) (interface{}, error) {
	s, err := enumReader(data, i)
	if err != nil {
		return nil, err
	}
	val := temporalPathTarget(s.(string))
	switch val {
	case TemporalPathTarget.EarliestArrival, TemporalPathTarget.EarliestDeparture, TemporalPathTarget.LatestDeparture, TemporalPathTarget.LatestArrival:
		return val, nil
	default:
		return nil, fmt.Errorf("unknown TemporalPathTarget value %q", val)
	}
}

func temporalReader(data *[]byte, i *int) (interface{}, error) {
	s, err := enumReader(data, i)
	if err != nil {
		return nil, err
	}
	val := temporal(s.(string))
	if !validTemporalSet[val] {
		return nil, fmt.Errorf("unknown Temporal value %q", val)
	}
	return val, nil
}

func temporalQuantifierReader(data *[]byte, i *int) (interface{}, error) {
	s, err := enumReader(data, i)
	if err != nil {
		return nil, err
	}
	val := temporalQuantifier(s.(string))
	switch val {
	case TemporalQuantifier.All, TemporalQuantifier.Any:
		return val, nil
	default:
		return nil, fmt.Errorf("unknown TemporalQuantifier value %q", val)
	}
}
