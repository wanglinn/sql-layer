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

package com.akiban.cserver.api.dml.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class ColumnSetTest {

    @Test
    public void pack1Byte() throws Exception {
        Set<Integer> columns = new HashSet<Integer>();
        columns.add( 6 );
        columns.add( 6 );

        assertBytes("[ 10000010 ]", columns);
    }

    @Test
    public void pack2BytesBoth() throws Exception {
        Set<Integer> columns = new HashSet<Integer>();
        columns.add( 0 );
        columns.add( 6 );
        columns.add( 9 );

        assertBytes("[ 10000010 01000000 ]", columns);
    }

    @Test
    public void pack2BytesSparse() throws Exception {
        Set<Integer> columns = new HashSet<Integer>();
        columns.add( 8 );

        assertBytes("[ 00000000 10000000 ]", columns);
    }

    @Test
    public void emptySet() {
        Set<Integer> empty = new HashSet<Integer>();
        assertNotNull("got null byte[]", ColumnSet.packToLegacy(empty));
        assertBytes("[ ]", empty);
    }

    private static void assertBytes(String expected, Set<Integer> actual) {
        final byte[] actualBytes =  ColumnSet.packToLegacy(actual);
        assertEquals("bytes", expected, bytesToHex(actualBytes));

        Set<Integer> unpacked = ColumnSet.unpackFromLegacy(actualBytes);
        assertEquals("unpacked set", actual, unpacked);
    }

    @Test
    public void testBytesToHex() {
        assertEquals("empty array", "[ ]", bytesToHex(new byte[] {} ));

        assertEquals("zero array", "[ 00000000 ]", bytesToHex(new byte[] {0} ));
        assertEquals("one array", "[ 10000000 ]", bytesToHex(new byte[] {1} ));
        assertEquals("one byte array", "[ 00010000 ]", bytesToHex(new byte[] {8} ));
        assertEquals("one byte array", "[ 11100000 10100000 ]", bytesToHex(new byte[] {7, 5} ));
        assertEquals("one byte array", "[ 00000000 10000000 ]", bytesToHex(new byte[] {(byte)0, 1} ));

    }

    private static String bytesToHex(byte[] actualBytes) {
        StringBuilder builder = new StringBuilder(4 + actualBytes.length*9 );
        builder.append("[ ");

        for(byte theByte : actualBytes) {
            for (int i=1; i <= 128; i <<= 1) {
                builder.append( (theByte & i) == i ? '1' : '0');
            }
            builder.append(' ');
        }

        builder.append(']');
        return builder.toString();
    }
}
