/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.sql.optimizer.rule.range;

import com.akiban.server.expression.std.Comparison;
import com.akiban.sql.optimizer.plan.ConditionExpression;
import com.akiban.sql.optimizer.plan.ConstantExpression;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static com.akiban.sql.optimizer.rule.range.TUtils.*;
import static org.junit.Assert.assertEquals;

public final class ColumnRangesTest {

    @Test
    public void colLtValue() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(firstName, Comparison.LT, value);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(RangeEndpoint.NULL_EXCLUSIVE, exclusive("joe")))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void valueLtCol() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(value, Comparison.LT, firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(exclusive("joe"), RangeEndpoint.UPPER_WILD))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void colLeValue() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(firstName, Comparison.LE, value);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(RangeEndpoint.NULL_EXCLUSIVE, inclusive("joe")))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void valueLeCol() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(value, Comparison.LE, firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(inclusive("joe"), RangeEndpoint.UPPER_WILD))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void colGtValue() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(firstName, Comparison.GT, value);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(exclusive("joe"), RangeEndpoint.UPPER_WILD))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void valueGtCol() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(value, Comparison.GT, firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(RangeEndpoint.NULL_EXCLUSIVE, exclusive("joe")))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void colGeValue() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(firstName, Comparison.GE, value);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(inclusive("joe"), RangeEndpoint.UPPER_WILD))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void valueGeCol() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(value, Comparison.GE, firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(RangeEndpoint.NULL_EXCLUSIVE, inclusive("joe")))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void colEqValue() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(firstName, Comparison.EQ, value);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(inclusive("joe"), inclusive("joe")))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void valueEqCol() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(value, Comparison.GE, firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(segment(inclusive("joe"), inclusive("joe")))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    ///


    @Test
    public void colNeValue() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(firstName, Comparison.NE, value);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(
                        segment(RangeEndpoint.NULL_EXCLUSIVE, exclusive("joe")),
                        segment(exclusive("joe"), RangeEndpoint.UPPER_WILD)
                )
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void valueNeCol() {
        ConstantExpression value = constant("joe");
        ConditionExpression compare = compare(value, Comparison.NE, firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(compare),
                Arrays.asList(
                        segment(RangeEndpoint.NULL_EXCLUSIVE, exclusive("joe")),
                        segment(exclusive("joe"), RangeEndpoint.UPPER_WILD)
                )
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(compare));
    }

    @Test
    public void columnIsNull() {
        ConditionExpression isNull = isNull(firstName);
        ColumnRanges expected = new ColumnRanges(
                firstName,
                Collections.singleton(isNull),
                Arrays.asList(segment(RangeEndpoint.NULL_INCLUSIVE, RangeEndpoint.NULL_INCLUSIVE))
        );
        assertEquals(expected, ColumnRanges.rangeAtNode(isNull));
    }

    @Test
    public void sinOfColumn() {
        ConditionExpression isNull = sin(firstName);
        ColumnRanges expected = null;
        assertEquals(expected, ColumnRanges.rangeAtNode(isNull));
    }
}
