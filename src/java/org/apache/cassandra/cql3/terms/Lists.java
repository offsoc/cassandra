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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.terms;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.cassandra.cql3.AssignmentTestable;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.cql3.Operation;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.UpdateParameters;
import org.apache.cassandra.cql3.VariableSpecifications;
import org.apache.cassandra.db.guardrails.Guardrails;
import org.apache.cassandra.db.marshal.MultiElementType;
import org.apache.cassandra.schema.ColumnMetadata;
import com.google.common.annotations.VisibleForTesting;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.ListType;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.utils.ByteBufferUtil;

import static org.apache.cassandra.cql3.terms.Constants.UNSET_VALUE;
import static org.apache.cassandra.cql3.statements.RequestValidations.checkFalse;
import static org.apache.cassandra.cql3.statements.RequestValidations.invalidRequest;
import static org.apache.cassandra.utils.Clock.Global.currentTimeMillis;
import static org.apache.cassandra.utils.TimeUUID.Generator.atUnixMillisAsBytes;

/**
 * Static helper methods and classes for lists.
 */
public abstract class Lists
{
    private Lists() {}

    public static ColumnSpecification indexSpecOf(ColumnSpecification column)
    {
        return new ColumnSpecification(column.ksName, column.cfName, new ColumnIdentifier("idx(" + column.name + ")", true), Int32Type.instance);
    }

    public static ColumnSpecification valueSpecOf(ColumnSpecification column)
    {
        return new ColumnSpecification(column.ksName, column.cfName, new ColumnIdentifier("value(" + column.name + ")", true), elementsType(column.type));
    }

    private static AbstractType<?> elementsType(AbstractType<?> type)
    {
        return ((ListType<?>) type.unwrap()).getElementsType();
    }

    /**
     * Tests that the list with the specified elements can be assigned to the specified column.
     *
     * @param receiver the receiving column
     * @param elements the list elements
     */
    public static AssignmentTestable.TestResult testListAssignment(ColumnSpecification receiver,
                                                                   List<? extends AssignmentTestable> elements)
    {
        if (!(receiver.type instanceof ListType))
            return AssignmentTestable.TestResult.NOT_ASSIGNABLE;

        // If there is no elements, we can't say it's an exact match (an empty list if fundamentally polymorphic).
        if (elements.isEmpty())
            return AssignmentTestable.TestResult.WEAKLY_ASSIGNABLE;

        ColumnSpecification valueSpec = valueSpecOf(receiver);
        return AssignmentTestable.TestResult.testAll(receiver.ksName, valueSpec, elements);
    }

    /**
     * Create a <code>String</code> representation of the list containing the specified elements.
     *
     * @param elements the list elements
     * @return a <code>String</code> representation of the list
     */
    public static String listToString(List<?> elements)
    {
        return listToString(elements, Object::toString);
    }

    /**
     * Create a <code>String</code> representation of the list from the specified items associated to
     * the list elements.
     *
     * @param items items associated to the list elements
     * @param mapper the mapper used to map the items to the <code>String</code> representation of the list elements
     * @return a <code>String</code> representation of the list
     */
    public static <T> String listToString(Iterable<T> items, java.util.function.Function<T, String> mapper)
    {
        return StreamSupport.stream(items.spliterator(), false)
                            .map(mapper)
                            .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Returns the exact ListType from the items if it can be known.
     *
     * @param items the items mapped to the list elements
     * @param mapper the mapper used to retrieve the element types from the items
     * @return the exact ListType from the items if it can be known or <code>null</code>
     */
    public static <T> ListType<?> getExactListTypeIfKnown(List<T> items,
                                                          java.util.function.Function<T, AbstractType<?>> mapper)
    {
        Optional<AbstractType<?>> type = items.stream().map(mapper).filter(Objects::nonNull).findFirst();
        return type.isPresent() ? ListType.getInstance(type.get(), false) : null;
    }

    public static <T> ListType<?> getPreferredCompatibleType(List<T> items,
                                                             java.util.function.Function<T, AbstractType<?>> mapper)
    {
        Set<AbstractType<?>> types = items.stream().map(mapper).filter(Objects::nonNull).collect(Collectors.toSet());
        AbstractType<?> type = AssignmentTestable.getCompatibleTypeIfKnown(types);
        return type == null ? null : ListType.getInstance(type, false);
    }

    public static class Literal extends Term.Raw
    {
        private final List<Term.Raw> elements;

        public Literal(List<Term.Raw> elements)
        {
            this.elements = elements;
        }

        public Term prepare(String keyspace, ColumnSpecification receiver) throws InvalidRequestException
        {
            validateAssignableTo(keyspace, receiver);

            ColumnSpecification valueSpec = Lists.valueSpecOf(receiver);
            List<Term> values = new ArrayList<>(elements.size());
            boolean allTerminal = true;
            for (Term.Raw rt : elements)
            {
                Term t = rt.prepare(keyspace, valueSpec);

                checkFalse(t.containsBindMarker(), "Invalid list literal for %s: bind variables are not supported inside collection literals", receiver.name);

                if (t instanceof Term.NonTerminal)
                    allTerminal = false;

                values.add(t);
            }
            MultiElements.DelayedValue value = new MultiElements.DelayedValue((MultiElementType<?>) receiver.type.unwrap(), values);
            return allTerminal ? value.bind(QueryOptions.DEFAULT) : value;
        }

        private void validateAssignableTo(String keyspace, ColumnSpecification receiver) throws InvalidRequestException
        {
            AbstractType<?> type = receiver.type.unwrap();

            if (!(type instanceof ListType))
                throw invalidRequest("Invalid list literal for %s of type %s", receiver.name, receiver.type.asCQL3Type());

            ColumnSpecification valueSpec = Lists.valueSpecOf(receiver);
            for (Term.Raw rt : elements)
            {
                if (!rt.testAssignment(keyspace, valueSpec).isAssignable())
                    throw invalidRequest("Invalid list literal for %s: value %s is not of type %s", receiver.name, rt, valueSpec.type.asCQL3Type());
            }
        }

        public AssignmentTestable.TestResult testAssignment(String keyspace, ColumnSpecification receiver)
        {
            return testListAssignment(receiver, elements);
        }

        @Override
        public AbstractType<?> getExactTypeIfKnown(String keyspace)
        {
            return getExactListTypeIfKnown(elements, p -> p.getExactTypeIfKnown(keyspace));
        }

        @Override
        public AbstractType<?> getCompatibleTypeIfKnown(String keyspace)
        {
            return Lists.getPreferredCompatibleType(elements, p -> p.getCompatibleTypeIfKnown(keyspace));
        }

        public String getText()
        {
            return listToString(elements, Term.Raw::getText);
        }
    }

    /**
     * For prepend, we need to be able to generate unique but decreasing time
     * UUIDs, which is a bit challenging. To do that, given a time in milliseconds,
     * we add a number representing the 100-nanoseconds precision and make sure
     * that within the same millisecond, that number is always decreasing.
     */
    static class PrecisionTime
    {
        // Our reference time (1 jan 2010, 00:00:00) in milliseconds.
        private static final long REFERENCE_TIME = 1262304000000L;
        static final int MAX_NANOS = 9999;
        private static final AtomicReference<PrecisionTime> last = new AtomicReference<>(new PrecisionTime(Long.MAX_VALUE, 0));

        public final long millis;
        public final int nanos;

        PrecisionTime(long millis, int nanos)
        {
            this.millis = millis;
            this.nanos = nanos;
        }

        static PrecisionTime getNext(long millis, int count)
        {
            if (count == 0)
                return last.get();

            while (true)
            {
                PrecisionTime current = last.get();

                final PrecisionTime next;
                if (millis < current.millis)
                {
                    next = new PrecisionTime(millis, MAX_NANOS - count);
                }
                else
                {
                    // in addition to being at the same millisecond, we handle the unexpected case of the millis parameter
                    // being in the past. That could happen if the Global.currentTimeMillis() not operating montonically
                    // or if one thread is just a really big loser in the compareAndSet game of life.
                    long millisToUse = millis <= current.millis ? millis : current.millis;

                    // if we will go below zero on the nanos, decrement the millis by one
                    final int nanosToUse;
                    if (current.nanos - count >= 0)
                    {
                        nanosToUse = current.nanos - count;
                    }
                    else
                    {
                        nanosToUse = MAX_NANOS - count;
                        millisToUse -= 1;
                    }

                    next = new PrecisionTime(millisToUse, nanosToUse);
                }

                if (last.compareAndSet(current, next))
                    return next;
            }
        }

        @VisibleForTesting
        static void set(long millis, int nanos)
        {
            last.set(new PrecisionTime(millis, nanos));
        }
    }

    public static class Setter extends Operation
    {
        public Setter(ColumnMetadata column, Term t)
        {
            super(column, t);
        }

        public void execute(DecoratedKey partitionKey, UpdateParameters params) throws InvalidRequestException
        {
            Term.Terminal value = t.bind(params.options);
            if (value == UNSET_VALUE)
                return;

            // delete + append
            if (column.type.isMultiCell())
                params.setComplexDeletionTimeForOverwrite(column);
            Appender.doAppend(value, column, params);
        }
    }

    private static int existingSize(Row row, ColumnMetadata column)
    {
        if (row == null)
            return 0;

        ComplexColumnData complexData = row.getComplexColumnData(column);
        return complexData == null ? 0 : complexData.cellsCount();
    }

    public static class SetterByIndex extends Operation
    {
        private final Term idx;

        public SetterByIndex(ColumnMetadata column, Term idx, Term t)
        {
            super(column, t);
            this.idx = idx;
        }

        @Override
        public boolean requiresRead()
        {
            return true;
        }

        @Override
        public void collectMarkerSpecification(VariableSpecifications boundNames)
        {
            super.collectMarkerSpecification(boundNames);
            idx.collectMarkerSpecification(boundNames);
        }

        public void execute(DecoratedKey partitionKey, UpdateParameters params) throws InvalidRequestException
        {
            // we should not get here for frozen lists
            assert column.type.isMultiCell() : "Attempted to set an individual element on a frozen list";

            Guardrails.readBeforeWriteListOperationsEnabled
            .ensureEnabled("Setting of list items by index requiring read before write", params.clientState);

            ByteBuffer index = idx.bindAndGet(params.options);
            ByteBuffer value = t.bindAndGet(params.options);

            if (index == null)
                throw new InvalidRequestException("Invalid null value for list index");
            if (index == ByteBufferUtil.UNSET_BYTE_BUFFER)
                throw new InvalidRequestException("Invalid unset value for list index");

            Row existingRow = params.getPrefetchedRow(partitionKey, params.currentClustering());
            int existingSize = existingSize(existingRow, column);
            int idx = ByteBufferUtil.toInt(index);
            if (existingSize == 0)
                throw new InvalidRequestException("Attempted to set an element on a list which is null");
            if (idx < 0 || idx >= existingSize)
                throw new InvalidRequestException(String.format("List index %d out of bound, list has size %d", idx, existingSize));

            CellPath elementPath = existingRow.getComplexColumnData(column).getCellByIndex(idx).path();
            if (value == null)
                params.addTombstone(column, elementPath);
            else if (value != ByteBufferUtil.UNSET_BYTE_BUFFER)
                params.addCell(column, elementPath, value);
        }
    }

    public static class Appender extends Operation
    {
        public Appender(ColumnMetadata column, Term t)
        {
            super(column, t);
        }

        public void execute(DecoratedKey partitionKey, UpdateParameters params) throws InvalidRequestException
        {
            assert column.type.isMultiCell() : "Attempted to append to a frozen list";
            Term.Terminal value = t.bind(params.options);
            doAppend(value, column, params);
        }

        static void doAppend(Term.Terminal value, ColumnMetadata column, UpdateParameters params) throws InvalidRequestException
        {
            ListType<?> type = (ListType<?>) column.type;

            if (value == null)
            {
                // for frozen lists, we're overwriting the whole cell value
                if (!type.isMultiCell())
                    params.addTombstone(column);

                // If we append null, do nothing. Note that for Setter, we've
                // already removed the previous value so we're good here too
                return;
            }

            List<ByteBuffer> elements = value.getElements();

            if (type.isMultiCell())
            {
                if (elements.isEmpty())
                    return;

                // Guardrails about collection size are only checked for the added elements without considering
                // already existent elements. This is done so to avoid read-before-write, having additional checks
                // during SSTable write.
                Guardrails.itemsPerCollection.guard(type.collectionSize(elements), column.name.toString(), false, params.clientState);

                int dataSize = 0;
                for (ByteBuffer buffer : elements)
                {
                    ByteBuffer uuid = ByteBuffer.wrap(params.nextTimeUUIDAsBytes());
                    Cell<?> cell = params.addCell(column, CellPath.create(uuid), buffer);
                    dataSize += cell.dataSize();
                }
                Guardrails.collectionListSize.guard(dataSize, column.name.toString(), false, params.clientState);
            }
            else
            {
                Guardrails.itemsPerCollection.guard(type.collectionSize(elements), column.name.toString(), false, params.clientState);
                Cell<?> cell = params.addCell(column, value.get());
                Guardrails.collectionListSize.guard(cell.dataSize(), column.name.toString(), false, params.clientState);
            }
        }
    }

    public static class Prepender extends Operation
    {
        public Prepender(ColumnMetadata column, Term t)
        {
            super(column, t);
        }

        public void execute(DecoratedKey partitionKey, UpdateParameters params) throws InvalidRequestException
        {
            assert column.type.isMultiCell() : "Attempted to prepend to a frozen list";
            Term.Terminal value = t.bind(params.options);
            if (value == null || value == UNSET_VALUE)
                return;

            List<ByteBuffer> toAdd = value.getElements();
            final int totalCount = toAdd.size();

            // we have to obey MAX_NANOS per batch - in the unlikely event a client has decided to prepend a list with
            // an insane number of entries.
            PrecisionTime pt = null;
            int remainingInBatch = 0;
            for (int i = totalCount - 1; i >= 0; i--)
            {
                if (remainingInBatch == 0)
                {
                    long time = PrecisionTime.REFERENCE_TIME - (currentTimeMillis() - PrecisionTime.REFERENCE_TIME);
                    remainingInBatch = Math.min(PrecisionTime.MAX_NANOS, i) + 1;
                    pt = PrecisionTime.getNext(time, remainingInBatch);
                }

                // TODO: is this safe as part of LWTs?
                ByteBuffer uuid = ByteBuffer.wrap(atUnixMillisAsBytes(pt.millis, (pt.nanos + remainingInBatch--)));
                params.addCell(column, CellPath.create(uuid), toAdd.get(i));
            }
        }
    }

    public static class Discarder extends Operation
    {
        public Discarder(ColumnMetadata column, Term t)
        {
            super(column, t);
        }

        @Override
        public boolean requiresRead()
        {
            return true;
        }

        public void execute(DecoratedKey partitionKey, UpdateParameters params) throws InvalidRequestException
        {
            assert column.type.isMultiCell() : "Attempted to delete from a frozen list";

            Guardrails.readBeforeWriteListOperationsEnabled
            .ensureEnabled("Removal of list items requiring read before write", params.clientState);

            // We want to call bind before possibly returning to reject queries where the value provided is not a list.
            Term.Terminal value = t.bind(params.options);

            Row existingRow = params.getPrefetchedRow(partitionKey, params.currentClustering());
            ComplexColumnData complexData = existingRow == null ? null : existingRow.getComplexColumnData(column);
            if (value == null || value == UNSET_VALUE || complexData == null)
                return;

            // Note: below, we will call 'contains' on this toDiscard list for each element of existingList.
            // Meaning that if toDiscard is big, converting it to a HashSet might be more efficient. However,
            // the read-before-write this operation requires limits its usefulness on big lists, so in practice
            // toDiscard will be small and keeping a list will be more efficient.
            List<ByteBuffer> toDiscard = value.getElements();
            for (Cell<?> cell : complexData)
            {
                if (toDiscard.contains(cell.buffer()))
                    params.addTombstone(column, cell.path());
            }
        }
    }

    public static class DiscarderByIndex extends Operation
    {
        public DiscarderByIndex(ColumnMetadata column, Term idx)
        {
            super(column, idx);
        }

        @Override
        public boolean requiresRead()
        {
            return true;
        }

        public void execute(DecoratedKey partitionKey, UpdateParameters params) throws InvalidRequestException
        {
            assert column.type.isMultiCell() : "Attempted to delete an item by index from a frozen list";

            Guardrails.readBeforeWriteListOperationsEnabled
            .ensureEnabled("Removal of list items by index requiring read before write", params.clientState);

            Term.Terminal index = t.bind(params.options);
            if (index == null)
                throw new InvalidRequestException("Invalid null value for list index");
            if (index == Constants.UNSET_VALUE)
                return;

            Row existingRow = params.getPrefetchedRow(partitionKey, params.currentClustering());
            int existingSize = existingSize(existingRow, column);
            int idx = ByteBufferUtil.toInt(index.get());
            if (existingSize == 0)
                throw new InvalidRequestException("Attempted to delete an element from a list which is null");
            if (idx < 0 || idx >= existingSize)
                throw new InvalidRequestException(String.format("List index %d out of bound, list has size %d", idx, existingSize));

            params.addTombstone(column, existingRow.getComplexColumnData(column).getCellByIndex(idx).path());
        }
    }
}
