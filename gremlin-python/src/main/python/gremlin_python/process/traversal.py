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

import copy
import calendar
import datetime
import warnings
from aenum import Enum
from .. import statics
from ..statics import long

class Traversal(object):
    def __init__(self, graph, traversal_strategies, bytecode):
        self.graph = graph
        self.traversal_strategies = traversal_strategies
        self.bytecode = bytecode
        self.traversers = None
        self.last_traverser = None

    def __repr__(self):
        return str(self.bytecode)

    def __eq__(self, other):
        if isinstance(other, self.__class__):
            return self.bytecode == other.bytecode
        else:
            return False

    def __iter__(self):
        return self

    def __next__(self):
        if self.traversers is None:
            self.traversal_strategies.apply_strategies(self)
        if self.last_traverser is None:
            self.last_traverser = next(self.traversers)
        object = self.last_traverser.object
        self.last_traverser.bulk = self.last_traverser.bulk - 1
        if self.last_traverser.bulk <= 0:
            self.last_traverser = None
        return object

    def toList(self):
        warnings.warn(
            "gremlin_python.process.Traversal.toList will be replaced by "
            "gremlin_python.process.Traversal.to_list.",
            DeprecationWarning)
        return self.to_list()

    def to_list(self):
        return list(iter(self))

    def toSet(self):
        warnings.warn(
            "gremlin_python.process.Traversal.toSet will be replaced by "
            "gremlin_python.process.Traversal.to_set.",
            DeprecationWarning)
        return self.to_set()

    def to_set(self):
        return set(iter(self))

    def iterate(self):
        self.bytecode.add_step("none")
        while True:
            try: self.next_traverser()
            except StopIteration: return self

    def nextTraverser(self):
        warnings.warn(
            "gremlin_python.process.Traversal.nextTraverser will be replaced by "
            "gremlin_python.process.Traversal.next_traverser.",
            DeprecationWarning)
        return self.next_traverser()

    def next_traverser(self):
        if self.traversers is None:
            self.traversal_strategies.apply_strategies(self)
        if self.last_traverser is None:
            return next(self.traversers)
        else:
            temp = self.last_traverser
            self.last_traverser = None
            return temp

    def hasNext(self):
        warnings.warn(
            "gremlin_python.process.Traversal.hasNext will be replaced by "
            "gremlin_python.process.Traversal.has_next.",
            DeprecationWarning)
        return self.has_next()

    def has_next(self):
        if self.traversers is None:
            self.traversal_strategies.apply_strategies(self)
        if self.last_traverser is None:
            try: self.last_traverser = next(self.traversers)
            except StopIteration: return False
        return not(self.last_traverser is None) and self.last_traverser.bulk > 0

    def next(self, amount=None):
        if amount is None:
            return self.__next__()
        else:
            count = 0
            tempList = []
            while count < amount:
                count = count + 1
                try: temp = self.__next__()
                except StopIteration: return tempList
                tempList.append(temp)
            return tempList

    def promise(self, cb=None):
        self.traversal_strategies.apply_async_strategies(self)
        future_traversal = self.remote_results
        future = type(future_traversal)()
        def process(f):
            try:
                traversal = f.result()
            except Exception as e:
                future.set_exception(e)
            else:
                self.traversers = iter(traversal.traversers)
                if cb:
                    try:
                        result = cb(self)
                    except Exception as e:
                        future.set_exception(e)
                    else:
                        future.set_result(result)
                else:
                    future.set_result(self)
        future_traversal.add_done_callback(process)
        return future


Barrier = Enum('Barrier', ' normSack norm_sack')

statics.add_static('normSack', Barrier.normSack)
statics.add_static('norm_sack', Barrier.norm_sack)

Cardinality = Enum('Cardinality', ' list_ set_ single')

statics.add_static('single', Cardinality.single)
statics.add_static('list_', Cardinality.list_)
statics.add_static('set_', Cardinality.set_)

Column = Enum('Column', ' keys values')

statics.add_static('keys', Column.keys)
statics.add_static('values', Column.values)

# alias from_ and to
Direction = Enum(
    value='Direction',
    names=[
        ('BOTH', 'BOTH'),
        ('IN', 'IN'),
        ('OUT', 'OUT'),
        ('from_', "OUT"),
        ('to', 'IN'),
    ],
)

statics.add_static('OUT', Direction.OUT)
statics.add_static('IN', Direction.IN)
statics.add_static('BOTH', Direction.BOTH)
statics.add_static('from_', Direction.OUT)
statics.add_static('to', Direction.IN)

GraphSONVersion = Enum('GraphSONVersion', ' V1_0 V2_0 V3_0')

statics.add_static('V1_0', GraphSONVersion.V1_0)
statics.add_static('V2_0', GraphSONVersion.V2_0)
statics.add_static('V3_0', GraphSONVersion.V3_0)

GryoVersion = Enum('GryoVersion', ' V1_0 V3_0')

statics.add_static('V1_0', GryoVersion.V1_0)
statics.add_static('V3_0', GryoVersion.V3_0)

DT = Enum('DT', ' second minute hour day')

statics.add_static('second', DT.second)
statics.add_static('minute', DT.minute)
statics.add_static('hour', DT.hour)
statics.add_static('day', DT.day)

Merge = Enum('Merge', ' on_create on_match out_v in_v')

statics.add_static('on_create', Merge.on_create)
statics.add_static('on_match', Merge.on_match)
statics.add_static('in_v', Merge.in_v)
statics.add_static('out_v', Merge.out_v)

Order = Enum('Order', ' asc desc shuffle')

statics.add_static('shuffle', Order.shuffle)
statics.add_static('asc', Order.asc)
statics.add_static('desc', Order.desc)

Pick = Enum('Pick', ' any_ none')

statics.add_static('any_', Pick.any_)
statics.add_static('none', Pick.none)

Pop = Enum('Pop', ' all_ first last mixed')

statics.add_static('first', Pop.first)
statics.add_static('last', Pop.last)
statics.add_static('all_', Pop.all_)
statics.add_static('mixed', Pop.mixed)

Scope = Enum('Scope', ' global_ local')

statics.add_static('global_', Scope.global_)
statics.add_static('local', Scope.local)


T = Enum('T', ' id id_ key label value')

statics.add_static('id', T.id)
statics.add_static('label', T.label)
statics.add_static('id_', T.id_)
statics.add_static('key', T.key)
statics.add_static('value', T.value)


Operator = Enum('Operator', ' addAll add_all and_ assign div max max_ min min_ minus mult or_ sum_ sumLong sum_long')

statics.add_static('sum_', Operator.sum_)
statics.add_static('minus', Operator.minus)
statics.add_static('mult', Operator.mult)
statics.add_static('div', Operator.div)
statics.add_static('min', Operator.min_)
statics.add_static('min_', Operator.min_)
statics.add_static('max_', Operator.max_)
statics.add_static('assign', Operator.assign)
statics.add_static('and_', Operator.and_)
statics.add_static('or_', Operator.or_)
statics.add_static('addAll', Operator.addAll)
statics.add_static('add_all', Operator.add_all)
statics.add_static('sum_long', Operator.sum_long)


class P(object):
    def __init__(self, operator, value, other=None):
        self.operator = operator
        self.value = value
        self.other = other

    @staticmethod
    def between(*args):
        return P("between", *args)

    @staticmethod
    def eq(*args):
        return P("eq", *args)

    @staticmethod
    def gt(*args):
        return P("gt", *args)

    @staticmethod
    def gte(*args):
        return P("gte", *args)

    @staticmethod
    def inside(*args):
        return P("inside", *args)

    @staticmethod
    def lt(*args):
        return P("lt", *args)

    @staticmethod
    def lte(*args):
        return P("lte", *args)

    @staticmethod
    def neq(*args):
        return P("neq", *args)

    @staticmethod
    def not_(*args):
        return P("not", *args)

    @staticmethod
    def outside(*args):
        return P("outside", *args)

    @staticmethod
    def test(*args):
        return P("test", *args)

    @staticmethod
    def within(*args):
        if len(args) == 1 and type(args[0]) == list:
            return P("within", args[0])
        elif len(args) == 1 and type(args[0]) == set:
            return P("within", list(args[0]))
        else:
            return P("within", list(args))
        
    @staticmethod
    def without(*args):
        if len(args) == 1 and type(args[0]) == list:
            return P("without", args[0])
        elif len(args) == 1 and type(args[0]) == set:
            return P("without", list(args[0]))
        else:
            return P("without", list(args))

    def and_(self, arg):
        return P("and", self, arg)

    def or_(self, arg):
        return P("or", self, arg)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.operator == other.operator and self.value == other.value and self.other == other.other

    def __repr__(self):
        return self.operator + "(" + str(self.value) + ")" if self.other is None else self.operator + "(" + str(self.value) + "," + str(self.other) + ")"


def between(*args):
    return P.between(*args)


def eq(*args):
    return P.eq(*args)


def gt(*args):
    return P.gt(*args)


def gte(*args):
    return P.gte(*args)


def inside(*args):
    return P.inside(*args)


def lt(*args):
    return P.lt(*args)


def lte(*args):
    return P.lte(*args)


def neq(*args):
    return P.neq(*args)


def not_(*args):
    return P.not_(*args)


def outside(*args):
    return P.outside(*args)


def within(*args):
    return P.within(*args)


def without(*args):
    return P.without(*args)


statics.add_static('between', between)

statics.add_static('eq', eq)

statics.add_static('gt', gt)

statics.add_static('gte', gte)

statics.add_static('inside', inside)

statics.add_static('lt', lt)

statics.add_static('lte', lte)

statics.add_static('neq', neq)

statics.add_static('not_', not_)

statics.add_static('outside', outside)

statics.add_static('within', within)

statics.add_static('without', without)


class TextP(P):
    def __init__(self, operator, value, other=None):
        P.__init__(self, operator, value, other)

    @staticmethod
    def containing(*args):
        return TextP("containing", *args)

    @staticmethod
    def endingWith(*args):
        warnings.warn(
            "gremlin_python.process.TextP.endingWith will be replaced by "
            "gremlin_python.process.TextP.ending_with.",
            DeprecationWarning)
        return TextP("endingWith", *args)

    @staticmethod
    def ending_with(*args):
        return TextP("endingWith", *args)

    @staticmethod
    def notContaining(*args):
        warnings.warn(
            "gremlin_python.process.TextP.notContaining will be replaced by "
            "gremlin_python.process.TextP.not_containing.",
            DeprecationWarning)
        return TextP("notContaining", *args)

    @staticmethod
    def not_containing(*args):
        return TextP("notContaining", *args)

    @staticmethod
    def notEndingWith(*args):
        warnings.warn(
            "gremlin_python.process.TextP.notEndingWith will be replaced by "
            "gremlin_python.process.TextP.not_ending_with.",
            DeprecationWarning)
        return TextP("notEndingWith", *args)

    @staticmethod
    def not_ending_with(*args):
        return TextP("notEndingWith", *args)

    @staticmethod
    def notStartingWith(*args):
        warnings.warn(
            "gremlin_python.process.TextP.notStartingWith will be replaced by "
            "gremlin_python.process.TextP.not_starting_With.",
            DeprecationWarning)
        return TextP("notStartingWith", *args)

    @staticmethod
    def not_starting_with(*args):
        return TextP("notStartingWith", *args)

    @staticmethod
    def startingWith(*args):
        warnings.warn(
            "gremlin_python.process.TextP.startingWith will be replaced by "
            "gremlin_python.process.TextP.startingWith.",
            DeprecationWarning)
        return TextP("startingWith", *args)

    @staticmethod
    def starting_with(*args):
        return TextP("startingWith", *args)

    @staticmethod
    def regex(*args):
        return TextP("regex", *args)

    @staticmethod
    def not_regex(*args):
        return TextP("notRegex", *args)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.operator == other.operator and self.value == other.value and self.other == other.other

    def __repr__(self):
        return self.operator + "(" + str(self.value) + ")" if self.other is None else self.operator + "(" + str(self.value) + "," + str(self.other) + ")"


def containing(*args):
    return TextP.containing(*args)


def endingWith(*args):
    return TextP.ending_with(*args)


def ending_with(*args):
    return TextP.ending_with(*args)


def notContaining(*args):
    return TextP.not_containing(*args)


def not_containing(*args):
    return TextP.not_containing(*args)


def notEndingWith(*args):
    return TextP.not_ending_with(*args)


def not_ending_with(*args):
    return TextP.not_ending_with(*args)


def notStartingWith(*args):
    return TextP.not_starting_with(*args)


def not_starting_with(*args):
    return TextP.not_starting_with(*args)


def startingWith(*args):
    return TextP.starting_with(*args)


def starting_with(*args):
    return TextP.starting_with(*args)

def regex(*args):
    return TextP.regex(*args)

def not_regex(*args):
    return TextP.not_regex(*args)

statics.add_static('containing', containing)

statics.add_static('endingWith', endingWith)

statics.add_static('ending_with', ending_with)

statics.add_static('notContaining', notContaining)

statics.add_static('not_containing', not_containing)

statics.add_static('notEndingWith', notEndingWith)

statics.add_static('not_ending_with', not_ending_with)

statics.add_static('notStartingWith', notStartingWith)

statics.add_static('not_starting_with', not_starting_with)

statics.add_static('startingWith', startingWith)

statics.add_static('starting_with', starting_with)

statics.add_static('regex', regex)

statics.add_static('not_regex', not_regex)


'''
TEMPORAL TYPES, ENUMS, AND PREDICATES
'''

LifetimeMode = Enum('LifetimeMode', 'REPLACE ADD DROP')

DropMode = Enum('DropMode', 'SOFT HARD')

statics.add_static('SOFT', DropMode.SOFT)
statics.add_static('HARD', DropMode.HARD)

TemporalPathTarget = Enum(
    'TemporalPathTarget',
    'EARLIEST_ARRIVAL EARLIEST_DEPARTURE LATEST_DEPARTURE LATEST_ARRIVAL'
)

statics.add_static('EARLIEST_ARRIVAL', TemporalPathTarget.EARLIEST_ARRIVAL)
statics.add_static('EARLIEST_DEPARTURE', TemporalPathTarget.EARLIEST_DEPARTURE)
statics.add_static('LATEST_DEPARTURE', TemporalPathTarget.LATEST_DEPARTURE)
statics.add_static('LATEST_ARRIVAL', TemporalPathTarget.LATEST_ARRIVAL)

TemporalQuantifier = Enum('TemporalQuantifier', 'ALL ANY')

Temporal = Enum(
    value='Temporal',
    names=[
        ('intersects', 'intersects'),
        ('disjoint', 'disjoint'),
        ('overlaps', 'overlaps'),
        ('precedes', 'precedes'),
        ('succeeds', 'succeeds'),
        ('overlappedBy', 'overlappedBy'),
        ('overlapped_by', 'overlappedBy'),
        ('meets', 'meets'),
        ('metBy', 'metBy'),
        ('met_by', 'metBy'),
        ('during', 'during'),
        ('contains', 'contains'),
        ('eq', 'eq'),
        ('starts', 'starts'),
        ('startedBy', 'startedBy'),
        ('started_by', 'startedBy'),
        ('finishes', 'finishes'),
        ('finishedBy', 'finishedBy'),
        ('finished_by', 'finishedBy'),
        ('generalized', 'generalized'),
    ],
)

_MIN_MILLIS = -9223372036854775808
_MAX_MILLIS = 9223372036854775807
_EPOCH_UTC = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)


def _assert_int64_millis(ms):
    if ms < _MIN_MILLIS or ms > _MAX_MILLIS:
        raise ValueError("Temporal value %d out of signed 64-bit long range [%d, %d]" % (ms, _MIN_MILLIS, _MAX_MILLIS))
    return ms


def _is_ascii_digits(s):
    return len(s) > 0 and all('0' <= c <= '9' for c in s)


def _datetime_to_epoch_millis(dt):
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=datetime.timezone.utc)
    else:
        dt = dt.astimezone(datetime.timezone.utc)
    td = dt - _EPOCH_UTC
    ms = (td.days * 86400 + td.seconds) * 1000 + (td.microseconds // 1000)
    return _assert_int64_millis(ms)


def _parse_iso_to_epoch_millis(text):
    if text.endswith('Z') or text.endswith('z'):
        text = text[:-1] + '+00:00'
    # Normalize fractional seconds of arbitrary length (e.g. .5 or .123456789) to 6 digits for Python < 3.11
    dot_idx = text.find('.')
    if dot_idx != -1:
        end_frac = dot_idx + 1
        while end_frac < len(text) and '0' <= text[end_frac] <= '9':
            end_frac += 1
        frac = text[dot_idx + 1:end_frac]
        if frac:
            norm_frac = (frac + '000000')[:6]
            text = text[:dot_idx + 1] + norm_frac + text[end_frac:]
    dt = datetime.datetime.fromisoformat(text)
    return _datetime_to_epoch_millis(dt)


def _to_epoch_millis(value, default_millis=None):
    if value is None:
        if default_millis is not None:
            return _assert_int64_millis(int(default_millis))
        raise ValueError("Temporal value cannot be None")
    if isinstance(value, bool):
        raise TypeError("Boolean is not a valid temporal value")
    if isinstance(value, int):
        return _assert_int64_millis(int(value))
    if isinstance(value, datetime.datetime):
        return _datetime_to_epoch_millis(value)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            raise ValueError("Temporal string cannot be empty")
        if (text[0] in '+-' and _is_ascii_digits(text[1:])) or _is_ascii_digits(text):
            return _assert_int64_millis(int(text))
        return _parse_iso_to_epoch_millis(text)
    raise TypeError("Unsupported temporal value type: %s" % type(value).__name__)


def _can_merge_range(left_start, left_end, right_start, right_end):
    if left_end < right_start:
        return False
    left_is_half_open = left_start != left_end
    right_is_point = right_start == right_end
    return not (left_is_half_open and right_is_point and left_end == right_start)


class Interval(object):
    def __init__(self, start_millis, end_millis):
        s = _to_epoch_millis(start_millis)
        e = _to_epoch_millis(end_millis)
        if e < s:
            raise ValueError("End time must not be before start time")
        self.start_millis = s
        self.end_millis = e

    def to_graphson_string(self):
        return "%d:%d" % (self.start_millis, self.end_millis)

    @staticmethod
    def from_graphson_string(text):
        parts = text.split(':')
        if len(parts) != 2:
            raise ValueError("Invalid Interval format: %s" % text)
        return Interval(int(parts[0]), int(parts[1]))

    def __eq__(self, other):
        return isinstance(other, Interval) and self.start_millis == other.start_millis and self.end_millis == other.end_millis

    def __hash__(self):
        return hash((self.start_millis, self.end_millis))

    def __repr__(self):
        return "Interval(%d, %d)" % (self.start_millis, self.end_millis)


class Lifetime(object):
    MIN_MILLIS = _MIN_MILLIS
    MAX_MILLIS = _MAX_MILLIS

    def __init__(self, intervals=None):
        if not intervals:
            self.intervals = ()
            return
        parsed = []
        for iv in intervals:
            if isinstance(iv, Interval):
                parsed.append((iv.start_millis, iv.end_millis))
            elif isinstance(iv, (tuple, list)) and len(iv) == 2:
                s = _to_epoch_millis(iv[0], _MIN_MILLIS)
                e = _to_epoch_millis(iv[1], _MAX_MILLIS)
                if e < s:
                    raise ValueError("End time must not be before start time")
                parsed.append((s, e))
            else:
                raise TypeError("Expected Interval or (start, end) pair, got %s" % type(iv).__name__)
        parsed.sort()
        merged = []
        cur_s, cur_e = parsed[0]
        for next_s, next_e in parsed[1:]:
            if _can_merge_range(cur_s, cur_e, next_s, next_e):
                cur_e = max(cur_e, next_e)
                if merged and _can_merge_range(merged[-1][0], merged[-1][1], cur_s, cur_e):
                    prev_s, prev_e = merged.pop()
                    cur_s = prev_s
                    cur_e = max(prev_e, cur_e)
            else:
                merged.append((cur_s, cur_e))
                cur_s, cur_e = next_s, next_e
        merged.append((cur_s, cur_e))
        self.intervals = tuple(Interval(s, e) for s, e in merged)

    @classmethod
    def empty(cls):
        return cls(())

    @classmethod
    def unbounded(cls):
        return cls((Interval(_MIN_MILLIS, _MAX_MILLIS),))

    @classmethod
    def from_(cls, start, end=None):
        s = _to_epoch_millis(start, _MIN_MILLIS)
        e = _to_epoch_millis(end, _MAX_MILLIS)
        return cls((Interval(s, e),))

    @classmethod
    def of(cls, *intervals):
        return cls(intervals)

    @classmethod
    def from_temporal_intervals(cls, text):
        if text is None or text == "":
            return cls.empty()
        ivs = []
        for part in text.split(','):
            part = part.strip()
            if not part:
                continue
            bounds = part.split(':')
            if len(bounds) != 2:
                raise ValueError("Invalid temporalIntervals component '%s'" % part)
            ivs.append(Interval(int(bounds[0]), int(bounds[1])))
        return cls(ivs)

    def to_temporal_intervals_string(self):
        if not self.intervals:
            return ""
        return ",".join("%d:%d" % (iv.start_millis, iv.end_millis) for iv in self.intervals)

    def is_empty(self):
        return len(self.intervals) == 0

    def is_unbounded(self):
        return len(self.intervals) == 1 and self.intervals[0].start_millis <= _MIN_MILLIS and self.intervals[0].end_millis >= _MAX_MILLIS

    @property
    def start_millis(self):
        if not self.intervals:
            raise ValueError("Lifetime is empty")
        return self.intervals[0].start_millis

    @property
    def end_millis(self):
        if not self.intervals:
            raise ValueError("Lifetime is empty")
        return self.intervals[-1].end_millis

    def __eq__(self, other):
        return isinstance(other, Lifetime) and self.intervals == other.intervals

    def __hash__(self):
        return hash(self.intervals)

    def __repr__(self):
        return "lifetime[" + " | ".join("%d,%d" % (iv.start_millis, iv.end_millis) for iv in self.intervals) + "]"


class GeneralizedRelation(object):
    _ALLEN_ZONES = {
        Temporal.precedes: (0, 0),
        Temporal.meets: (0, 1),
        Temporal.overlaps: (0, 2),
        Temporal.finishedBy: (0, 3),
        Temporal.contains: (0, 4),
        Temporal.starts: (1, 2),
        Temporal.eq: (1, 3),
        Temporal.startedBy: (1, 4),
        Temporal.during: (2, 2),
        Temporal.finishes: (2, 3),
        Temporal.overlappedBy: (2, 4),
        Temporal.metBy: (3, 4),
        Temporal.succeeds: (4, 4),
    }

    def __init__(self, p, q, zones):
        p = int(p)
        q = int(q)
        if p < 1 or q < 1:
            raise ValueError("Arities p and q must be >= 1")
        z_tuple = tuple(int(z) for z in zones)
        if len(z_tuple) != p:
            raise ValueError("Expected %d zone values, got %d" % (p, len(z_tuple)))
        max_zone = 2 * q
        for i, z in enumerate(z_tuple):
            if z < 0 or z > max_zone:
                raise ValueError("Zone index out of bounds [0, %d]: %d" % (max_zone, z))
            if i > 0:
                prev = z_tuple[i - 1]
                if z < prev:
                    raise ValueError("Zones must be non-decreasing: %r" % (z_tuple,))
                if (z & 1) == 1 and z == prev:
                    raise ValueError("Odd zone %d may occur at most once" % z)
        self.p = p
        self.q = q
        self.zones = z_tuple

    @classmethod
    def of(cls, q, *zones):
        return cls(len(zones), q, zones)

    @classmethod
    def identity(cls, p):
        return cls(p, p, [2 * i + 1 for i in range(p)])

    @classmethod
    def from_allen(cls, temporal):
        if temporal not in cls._ALLEN_ZONES:
            raise ValueError("Unsupported Allen Temporal operator: %r" % (temporal,))
        return cls(2, 2, cls._ALLEN_ZONES[temporal])

    def to_graphson_string(self):
        return "%d,%d:%s" % (self.p, self.q, ",".join(str(z) for z in self.zones))

    @classmethod
    def from_graphson_string(cls, text):
        pq_str, zones_str = text.split(':')
        p_str, q_str = pq_str.split(',')
        zones = [int(z) for z in zones_str.split(',')]
        return cls(int(p_str), int(q_str), zones)

    def __eq__(self, other):
        return isinstance(other, GeneralizedRelation) and self.p == other.p and self.q == other.q and self.zones == other.zones

    def __hash__(self):
        return hash((self.p, self.q, self.zones))

    def __repr__(self):
        return "GeneralizedRelation(%d, %d, %r)" % (self.p, self.q, list(self.zones))


class GeneralizedIntervalRelation(object):
    def __init__(self, lower, upper, p=None, q=None, empty=False):
        if empty:
            if p is None or q is None or int(p) < 1 or int(q) < 1:
                raise ValueError("Arities p and q must be >= 1 for empty GeneralizedIntervalRelation")
            self.p = int(p)
            self.q = int(q)
            self.lower = None
            self.upper = None
            self.empty_flag = True
        else:
            if not isinstance(lower, GeneralizedRelation) or not isinstance(upper, GeneralizedRelation):
                raise TypeError("lower and upper must be GeneralizedRelation instances")
            if lower.p != upper.p or lower.q != upper.q:
                raise ValueError("lower and upper must have matching (p, q) arities")
            for i in range(lower.p):
                if lower.zones[i] > upper.zones[i]:
                    raise ValueError("Lower bound must be <= upper bound in lattice order: %r vs %r" % (lower, upper))
            self.p = lower.p
            self.q = lower.q
            self.lower = lower
            self.upper = upper
            self.empty_flag = False

    @classmethod
    def empty(cls, p, q):
        return cls(None, None, p=p, q=q, empty=True)

    def is_empty(self):
        return self.empty_flag

    def to_graphson_string(self):
        if self.empty_flag:
            return "%d,%d:EMPTY" % (self.p, self.q)
        return "%d,%d:%s:%s" % (
            self.p,
            self.q,
            ",".join(str(z) for z in self.lower.zones),
            ",".join(str(z) for z in self.upper.zones),
        )

    @classmethod
    def from_graphson_string(cls, text):
        parts = text.split(':')
        p_str, q_str = parts[0].split(',')
        p, q = int(p_str), int(q_str)
        if parts[1] == "EMPTY":
            return cls.empty(p, q)
        lower_zones = [int(z) for z in parts[1].split(',')]
        upper_zones = [int(z) for z in parts[2].split(',')]
        return cls(GeneralizedRelation(p, q, lower_zones), GeneralizedRelation(p, q, upper_zones))

    def __eq__(self, other):
        return (isinstance(other, GeneralizedIntervalRelation)
                and self.p == other.p and self.q == other.q
                and self.empty_flag == other.empty_flag
                and self.lower == other.lower and self.upper == other.upper)

    def __hash__(self):
        return hash((self.p, self.q, self.empty_flag, self.lower, self.upper))

    def __repr__(self):
        if self.empty_flag:
            return "GeneralizedIntervalRelation.empty(%d, %d)" % (self.p, self.q)
        return "GeneralizedIntervalRelation(%r, %r)" % (self.lower, self.upper)


class TemporalP(P):
    def __init__(self, bi_predicate, value, generalized_relation=None,
                 generalized_interval_relation=None,
                 quantifier=TemporalQuantifier.ALL, negated=False):
        if isinstance(bi_predicate, str):
            bi_predicate = Temporal[bi_predicate]
        if not isinstance(bi_predicate, Temporal):
            raise TypeError("bi_predicate must be a Temporal enum, got %s" % type(bi_predicate).__name__)
        if (bi_predicate == Temporal.generalized
                and generalized_relation is None
                and generalized_interval_relation is None
                and isinstance(value, (list, tuple))
                and len(value) == 2):
            target, spec = value[0], value[1]
            if isinstance(spec, GeneralizedRelation):
                value = target
                generalized_relation = spec
            elif isinstance(spec, GeneralizedIntervalRelation):
                value = target
                generalized_interval_relation = spec
        P.__init__(self, bi_predicate.name, value)
        self.bi_predicate = bi_predicate
        self.generalized_relation = generalized_relation
        self.generalized_interval_relation = generalized_interval_relation
        self.quantifier = quantifier
        self.negated = bool(negated)

    def any(self):
        self.quantifier = TemporalQuantifier.ANY
        return self

    def all(self):
        self.quantifier = TemporalQuantifier.ALL
        return self

    def negate(self):
        if not self.negated and self.quantifier == TemporalQuantifier.ALL:
            if self.bi_predicate == Temporal.intersects:
                return TemporalP(Temporal.disjoint, self.value)
            if self.bi_predicate == Temporal.disjoint:
                return TemporalP(Temporal.intersects, self.value)
        return TemporalP(
            self.bi_predicate,
            self.value,
            generalized_relation=self.generalized_relation,
            generalized_interval_relation=self.generalized_interval_relation,
            quantifier=self.quantifier,
            negated=not self.negated,
        )

    def to_wire_parts(self):
        is_any = self.quantifier == TemporalQuantifier.ANY
        is_neg = self.negated
        if self.bi_predicate == Temporal.generalized:
            if is_neg:
                pred_name = "notAnyGeneralized" if is_any else "notGeneralized"
            else:
                pred_name = "anyGeneralized" if is_any else "generalized"
            if self.generalized_relation is not None:
                return pred_name, [self.value, self.generalized_relation]
            if self.generalized_interval_relation is not None:
                return pred_name, [self.value, self.generalized_interval_relation]
            return pred_name, [self.value]
        if is_any or is_neg:
            pred_name = ("notAny" if is_any else "not") if is_neg else "any"
            return pred_name, [self.bi_predicate, self.value]
        return self.bi_predicate.name, [self.value]

    @staticmethod
    def from_wire_parts(pred_name, args):
        if pred_name in ("any", "not", "notAny", "all") and len(args) == 2:
            op = args[0] if isinstance(args[0], Temporal) else Temporal[str(args[0])]
            tp = TemporalP(op, args[1])
            if pred_name in ("any", "notAny"):
                tp = tp.any()
            if pred_name in ("not", "notAny"):
                tp = tp.negate()
            return tp
        if pred_name in ("generalized", "anyGeneralized", "notGeneralized", "notAnyGeneralized"):
            if len(args) == 2 and isinstance(args[1], GeneralizedRelation):
                tp = TemporalP(Temporal.generalized, args[0], generalized_relation=args[1])
            elif len(args) == 2 and isinstance(args[1], GeneralizedIntervalRelation):
                tp = TemporalP(Temporal.generalized, args[0], generalized_interval_relation=args[1])
            else:
                tp = TemporalP(Temporal.generalized, args[0] if len(args) == 1 else args)
            if pred_name in ("anyGeneralized", "notAnyGeneralized"):
                tp = tp.any()
            if pred_name in ("notGeneralized", "notAnyGeneralized"):
                tp = tp.negate()
            return tp
        return TemporalP(Temporal[pred_name], args[0] if len(args) == 1 else args)

    @staticmethod
    def intersects(value):
        return TemporalP(Temporal.intersects, value)

    @staticmethod
    def disjoint(value):
        return TemporalP(Temporal.disjoint, value)

    @staticmethod
    def overlaps(value):
        return TemporalP(Temporal.overlaps, value)

    @staticmethod
    def precedes(value):
        return TemporalP(Temporal.precedes, value)

    @staticmethod
    def succeeds(value):
        return TemporalP(Temporal.succeeds, value)

    @staticmethod
    def overlapped_by(value):
        return TemporalP(Temporal.overlappedBy, value)

    overlappedBy = overlapped_by

    @staticmethod
    def meets(value):
        return TemporalP(Temporal.meets, value)

    @staticmethod
    def met_by(value):
        return TemporalP(Temporal.metBy, value)

    metBy = met_by

    @staticmethod
    def during(value):
        return TemporalP(Temporal.during, value)

    @staticmethod
    def contains(value):
        return TemporalP(Temporal.contains, value)

    @staticmethod
    def eq(value):
        return TemporalP(Temporal.eq, value)

    @staticmethod
    def starts(value):
        return TemporalP(Temporal.starts, value)

    @staticmethod
    def started_by(value):
        return TemporalP(Temporal.startedBy, value)

    startedBy = started_by

    @staticmethod
    def finishes(value):
        return TemporalP(Temporal.finishes, value)

    @staticmethod
    def finished_by(value):
        return TemporalP(Temporal.finishedBy, value)

    finishedBy = finished_by

    @staticmethod
    def between(lower, upper, value):
        lower_rel = GeneralizedRelation.from_allen(lower)
        upper_rel = GeneralizedRelation.from_allen(upper)
        return TemporalP(Temporal.generalized, value,
                         generalized_interval_relation=GeneralizedIntervalRelation(lower_rel, upper_rel))

    @staticmethod
    def generalized(value, relation=None):
        if isinstance(relation, GeneralizedRelation):
            return TemporalP(Temporal.generalized, value, generalized_relation=relation)
        if isinstance(relation, GeneralizedIntervalRelation):
            return TemporalP(Temporal.generalized, value, generalized_interval_relation=relation)
        if relation is not None:
            raise TypeError("relation must be GeneralizedRelation or GeneralizedIntervalRelation")
        return TemporalP(Temporal.generalized, value)

    @staticmethod
    def any_generalized(value, relation=None):
        return TemporalP.generalized(value, relation).any()

    anyGeneralized = any_generalized

    @staticmethod
    def not_generalized(value, relation=None):
        return TemporalP.generalized(value, relation).negate()

    notGeneralized = not_generalized

    @staticmethod
    def not_any_generalized(value, relation=None):
        return TemporalP.generalized(value, relation).any().negate()

    notAnyGeneralized = not_any_generalized

    @staticmethod
    def any_of(op, value):
        return TemporalP(op, value).any()

    @staticmethod
    def all_of(op, value):
        return TemporalP(op, value).all()

    @staticmethod
    def not_(op, value):
        return TemporalP(op, value).negate()

    @staticmethod
    def not_any(op, value):
        return TemporalP(op, value).any().negate()

    notAny = not_any

    def __eq__(self, other):
        return (isinstance(other, TemporalP)
                and self.bi_predicate == other.bi_predicate
                and self.value == other.value
                and self.quantifier == other.quantifier
                and self.negated == other.negated
                and self.generalized_relation == other.generalized_relation
                and self.generalized_interval_relation == other.generalized_interval_relation)

    def __repr__(self):
        pred, args = self.to_wire_parts()
        return "%s(%s)" % (pred, ", ".join(repr(a) for a in args))


class TemporalPageRank(object):

    alpha = "~tinkerpop.temporalPageRank.alpha"

    beta = "~tinkerpop.temporalPageRank.beta"

    propertyName = "~tinkerpop.temporalPageRank.propertyName"

    property_name = "~tinkerpop.temporalPageRank.propertyName"

    normalize = "~tinkerpop.temporalPageRank.normalize"

    bucketMillis = "~tinkerpop.temporalPageRank.bucketMillis"

    bucket_millis = "~tinkerpop.temporalPageRank.bucketMillis"


class TemporalShortestPath(object):

    target = "gremlin.temporalShortestPathVertexProgram.target"

    initialTime = "gremlin.temporalShortestPathVertexProgram.initialTime"

    initial_time = "gremlin.temporalShortestPathVertexProgram.initialTime"

    targetVertexFilter = "gremlin.temporalShortestPathVertexProgram.targetVertexFilter"

    target_vertex_filter = "gremlin.temporalShortestPathVertexProgram.targetVertexFilter"

    edgeTraversal = "gremlin.temporalShortestPathVertexProgram.edgeTraversal"

    edge_traversal = "gremlin.temporalShortestPathVertexProgram.edgeTraversal"

    maxDistance = "gremlin.temporalShortestPathVertexProgram.maxDistance"

    max_distance = "gremlin.temporalShortestPathVertexProgram.maxDistance"

    includeEdges = "gremlin.temporalShortestPathVertexProgram.includeEdges"

    include_edges = "gremlin.temporalShortestPathVertexProgram.includeEdges"


class TemporalOptions(object):

    quantifier = "~tinkerpop.temporal.quantifier"


'''
IO
'''


class IO(object):

    graphml = "graphml"

    graphson = "graphson"

    gryo = "gryo"

    reader = "~tinkerpop.io.reader"

    registry = "~tinkerpop.io.registry"

    writer = "~tinkerpop.io.writer"


'''
ConnectedComponent
'''


class ConnectedComponent(object):

    component = "gremlin.connectedComponentVertexProgram.component"

    edges = "~tinkerpop.connectedComponent.edges"

    propertyName = "~tinkerpop.connectedComponent.propertyName"

    property_name = "~tinkerpop.connectedComponent.propertyName"


'''
ShortestPath
'''


class ShortestPath(object):

    distance = "~tinkerpop.shortestPath.distance"

    edges = "~tinkerpop.shortestPath.edges"

    includeEdges = "~tinkerpop.shortestPath.includeEdges"

    include_edges = "~tinkerpop.shortestPath.includeEdges"

    maxDistance = "~tinkerpop.shortestPath.maxDistance"

    max_distance = "~tinkerpop.shortestPath.maxDistance"

    target = "~tinkerpop.shortestPath.target"


'''
PageRank
'''


class PageRank(object):

    edges = "~tinkerpop.pageRank.edges"

    propertyName = "~tinkerpop.pageRank.propertyName"

    property_name = "~tinkerpop.pageRank.propertyName"

    times = "~tinkerpop.pageRank.times"


'''
PeerPressure
'''


class PeerPressure(object):

    edges = "~tinkerpop.peerPressure.edges"

    propertyName = "~tinkerpop.peerPressure.propertyName"

    property_name = "~tinkerpop.pageRank.propertyName"

    times = "~tinkerpop.peerPressure.times"


'''
TRAVERSER
'''


class Traverser(object):
    def __init__(self, object, bulk=None):
        if bulk is None:
            bulk = long(1)
        self.object = object
        self.bulk = bulk

    def __repr__(self):
        return str(self.object)

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.object == other.object


'''
TRAVERSAL STRATEGIES
'''


class TraversalStrategies(object):
    global_cache = {}

    def __init__(self, traversal_strategies=None):
        self.traversal_strategies = traversal_strategies.traversal_strategies if traversal_strategies is not None else []

    def add_strategies(self, traversal_strategies):
        self.traversal_strategies = self.traversal_strategies + traversal_strategies

    def apply_strategies(self, traversal):
        for traversal_strategy in self.traversal_strategies:
            traversal_strategy.apply(traversal)

    def apply_async_strategies(self, traversal):
        for traversal_strategy in self.traversal_strategies:
            traversal_strategy.apply_async(traversal)

    def __repr__(self):
        return str(self.traversal_strategies)


class TraversalStrategy(object):
    def __init__(self, strategy_name=None, configuration=None, fqcn=None):
        self.fqcn = fqcn
        self.strategy_name = type(self).__name__ if strategy_name is None else strategy_name
        self.configuration = {} if configuration is None else configuration

    def apply(self, traversal):
        return

    def apply_async(self, traversal):
        return

    def __eq__(self, other):
        return isinstance(other, self.__class__)

    def __hash__(self):
        return hash(self.strategy_name)

    def __repr__(self):
        return self.strategy_name


'''
BYTECODE
'''


class Bytecode(object):
    def __init__(self, bytecode=None):
        self.source_instructions = []
        self.step_instructions = []
        self.bindings = {}
        if bytecode is not None:
            self.source_instructions = list(bytecode.source_instructions)
            self.step_instructions = list(bytecode.step_instructions)

    def add_source(self, source_name, *args):
        instruction = [source_name]
        for arg in args:
            instruction.append(self.__convertArgument(arg))
        self.source_instructions.append(instruction)

    def add_step(self, step_name, *args):
        instruction = [step_name]
        for arg in args:
            instruction.append(self.__convertArgument(arg))
        self.step_instructions.append(instruction)

    def __eq__(self, other):
        if isinstance(other, self.__class__):
            return self.source_instructions == other.source_instructions and self.step_instructions == other.step_instructions
        else:
            return False

    def __copy__(self):
        bb = Bytecode()
        bb.source_instructions = self.source_instructions
        bb.step_instructions = self.step_instructions
        bb.bindings = self.bindings
        return bb

    def __deepcopy__(self, memo={}):
        bb = Bytecode()
        bb.source_instructions = copy.deepcopy(self.source_instructions, memo)
        bb.step_instructions = copy.deepcopy(self.step_instructions, memo)
        bb.bindings = copy.deepcopy(self.bindings, memo)
        return bb

    def __convertArgument(self, arg):
        if isinstance(arg, Traversal):
            if arg.graph is not None:
                raise TypeError("The child traversal of " + str(arg) + " was not spawned anonymously - use the __ class rather than a TraversalSource to construct the child traversal")
            self.bindings.update(arg.bytecode.bindings)
            return arg.bytecode
        elif isinstance(arg, dict):
            newDict = {}
            for key in arg:
                newDict[self.__convertArgument(key)] = self.__convertArgument(arg[key])
            return newDict
        elif isinstance(arg, list):
            newList = []
            for item in arg:
                newList.append(self.__convertArgument(item))
            return newList
        elif isinstance(arg, set):
            newSet = set()
            for item in arg:
                newSet.add(self.__convertArgument(item))
            return newSet
        elif isinstance(arg, Binding):
            self.bindings[arg.key] = arg.value
            return Binding(arg.key, self.__convertArgument(arg.value))
        else:
            return arg

    def __repr__(self):
        return (str(self.source_instructions) if len(self.source_instructions) > 0 else "") + \
               (str(self.step_instructions) if len(self.step_instructions) > 0 else "")

    @staticmethod
    def _create_graph_op(name, *values):
        bc = Bytecode()
        bc.add_source(name, *values)
        return bc

    @staticmethod
    class GraphOp:
        @staticmethod
        def commit():
            return Bytecode._create_graph_op("tx", "commit")

        @staticmethod
        def rollback():
            return Bytecode._create_graph_op("tx", "rollback")


class CardinalityValue(Bytecode):
    def __init__(self, cardinality, val):
        super().__init__()
        self.add_source("CardinalityValueTraversal", cardinality, val)

    @classmethod
    def single(cls, val):
        return CardinalityValue(Cardinality.single, val)

    @classmethod
    def list_(cls, val):
        return CardinalityValue(Cardinality.list_, val)

    @classmethod
    def set_(cls, val):
        return CardinalityValue(Cardinality.set_, val)


'''
BINDINGS
'''


class Bindings(object):

    @staticmethod
    def of(key, value):
        if not isinstance(key, str):
            raise TypeError("Key must be str")
        return Binding(key, value)


class Binding(object):
    def __init__(self, key, value):
        self.key = key
        self.value = value

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.key == other.key and self.value == other.value

    def __hash__(self):
        return hash(self.key) + hash(self.value)

    def __repr__(self):
        return "binding[" + self.key + "=" + str(self.value) + "]"


'''
WITH OPTIONS
'''


class WithOptions(object):

    tokens = "~tinkerpop.valueMap.tokens"

    none = 0

    ids = 1

    labels = 2

    keys = 4

    values = 8

    all = 15

    indexer = "~tinkerpop.index.indexer"

    list = 0

    map = 1

