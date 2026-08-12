/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreOrderedIndexPredicate

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.Optional;

import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Converts supported qualifier shapes into index-candidate decisions.
 *
 * <p>This class owns only qualifier interpretation and B-tree key-bound
 * selection. Container access, MVCC visibility, and logical row-id authority
 * remain in {@link MvccRawStoreOrderedIndex}.</p>
 */
final class MvccRawStoreOrderedIndexPredicate {
    private MvccRawStoreOrderedIndexPredicate() {
    }


    static Predicate equality(int columnId, StoreDataValue value) throws StandardException {
        return new EqualityPredicate(
                columnId,
                StoreValueCopySupport.cloneValue(value));
    }

    static Optional<Predicate> from(Qualifier[][] qualifiers) throws StandardException {
        Optional<Predicate> equality = equality(qualifiers);
        return equality.isPresent() ? equality : range(qualifiers);
    }

    private static Optional<Predicate> equality(Qualifier[][] qualifiers)
            throws StandardException {
        if (qualifiers == null || qualifiers.length == 0) {
            return Optional.empty();
        }
        for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
            Qualifier[] andTerm = qualifiers[andTermIndex];
            if (andTerm == null || andTerm.length == 0) {
                continue;
            }
            if (andTermIndex > 0 && andTerm.length != 1) {
                return Optional.empty();
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier == null
                        || qualifier.getColumnId() < 0
                        || qualifier.getOperator() != StoreOrderable.ORDER_OP_EQUALS
                        || qualifier.negateCompareResult()) {
                    continue;
                }
                StoreDataValue orderable = qualifier.getOrderable();
                if (orderable == null) {
                    return Optional.empty();
                }
                return Optional.of(new EqualityPredicate(
                        qualifier.getColumnId(),
                        StoreValueCopySupport.cloneValue(orderable)));
            }
        }
        return Optional.empty();
    }

    private static Optional<Predicate> range(Qualifier[][] qualifiers)
            throws StandardException {
        if (qualifiers == null || qualifiers.length == 0) {
            return Optional.empty();
        }
        int column = -1;
        StoreDataValue lower = null;
        boolean lowerInclusive = true;
        StoreDataValue upper = null;
        boolean upperInclusive = true;
        boolean sawBound = false;

        for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
            Qualifier[] andTerm = qualifiers[andTermIndex];
            if (andTerm == null || andTerm.length == 0) {
                return Optional.empty();
            }
            if (andTermIndex > 0 && andTerm.length != 1) {
                return Optional.empty();
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier == null || qualifier.getColumnId() < 0) {
                    return Optional.empty();
                }
                int operator = normalizedRangeOperator(
                        qualifier.getOperator(),
                        qualifier.negateCompareResult());
                if (operator == Integer.MIN_VALUE) {
                    return Optional.empty();
                }
                if (column == -1) {
                    column = qualifier.getColumnId();
                } else if (column != qualifier.getColumnId()) {
                    return Optional.empty();
                }
                StoreDataValue orderable = qualifier.getOrderable();
                if (orderable == null) {
                    return Optional.empty();
                }
                StoreDataValue bound = StoreValueCopySupport.cloneValue(orderable);
                switch (operator) {
                    case StoreOrderable.ORDER_OP_GREATERTHAN -> {
                        BoundChoice choice = chooseLower(lower, lowerInclusive, bound, false);
                        lower = choice.value();
                        lowerInclusive = choice.inclusive();
                        sawBound = true;
                    }
                    case StoreOrderable.ORDER_OP_GREATEROREQUALS -> {
                        BoundChoice choice = chooseLower(lower, lowerInclusive, bound, true);
                        lower = choice.value();
                        lowerInclusive = choice.inclusive();
                        sawBound = true;
                    }
                    case StoreOrderable.ORDER_OP_LESSTHAN -> {
                        BoundChoice choice = chooseUpper(upper, upperInclusive, bound, false);
                        upper = choice.value();
                        upperInclusive = choice.inclusive();
                        sawBound = true;
                    }
                    case StoreOrderable.ORDER_OP_LESSOREQUALS -> {
                        BoundChoice choice = chooseUpper(upper, upperInclusive, bound, true);
                        upper = choice.value();
                        upperInclusive = choice.inclusive();
                        sawBound = true;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }
        }
        return !sawBound || column < 0
                ? Optional.empty()
                : Optional.of(new RangePredicate(
                        column,
                        lower,
                        lowerInclusive,
                        upper,
                        upperInclusive));
    }

    private static int normalizedRangeOperator(int operator, boolean negated) {
        if (!negated) {
            return operator;
        }
        return switch (operator) {
            case StoreOrderable.ORDER_OP_LESSTHAN -> StoreOrderable.ORDER_OP_GREATEROREQUALS;
            case StoreOrderable.ORDER_OP_LESSOREQUALS -> StoreOrderable.ORDER_OP_GREATERTHAN;
            case StoreOrderable.ORDER_OP_GREATERTHAN -> StoreOrderable.ORDER_OP_LESSOREQUALS;
            case StoreOrderable.ORDER_OP_GREATEROREQUALS -> StoreOrderable.ORDER_OP_LESSTHAN;
            default -> Integer.MIN_VALUE;
        };
    }

    private static BoundChoice chooseLower(
            StoreDataValue current,
            boolean currentInclusive,
            StoreDataValue candidate,
            boolean candidateInclusive) throws StandardException {
        if (current == null) {
            return new BoundChoice(candidate, candidateInclusive);
        }
        int comparison = StoreTypeUtil.compare(candidate, current, true);
        if (comparison > 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
            return new BoundChoice(candidate, candidateInclusive);
        }
        return new BoundChoice(current, currentInclusive);
    }

    private static BoundChoice chooseUpper(
            StoreDataValue current,
            boolean currentInclusive,
            StoreDataValue candidate,
            boolean candidateInclusive) throws StandardException {
        if (current == null) {
            return new BoundChoice(candidate, candidateInclusive);
        }
        int comparison = StoreTypeUtil.compare(candidate, current, true);
        if (comparison < 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
            return new BoundChoice(candidate, candidateInclusive);
        }
        return new BoundChoice(current, currentInclusive);
    }

    sealed interface Predicate permits EqualityPredicate, RangePredicate {
        int columnId();

        StoreDataValue lowerBound();

        boolean lowerInclusive();

        StoreDataValue upperBound();

        boolean upperInclusive();

    }

    private record EqualityPredicate(int columnId, StoreDataValue value) implements Predicate {
        @Override
        public StoreDataValue lowerBound() {
            return value;
        }

        @Override
        public boolean lowerInclusive() {
            return true;
        }

        @Override
        public StoreDataValue upperBound() {
            return value;
        }

        @Override
        public boolean upperInclusive() {
            return true;
        }
    }

    private record RangePredicate(
            int columnId,
            StoreDataValue lower,
            boolean lowerInclusive,
            StoreDataValue upper,
            boolean upperInclusive) implements Predicate {
        @Override
        public StoreDataValue lowerBound() {
            return lower;
        }

        @Override
        public StoreDataValue upperBound() {
            return upper;
        }
    }

    private record BoundChoice(StoreDataValue value, boolean inclusive) {
    }
}
