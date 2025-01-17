/*
 * Copyright (c) 2002-2019 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.cache;

import org.neo4j.unsafe.impl.internal.dragons.UnsafeUtil;

/**
 * Off-heap version of {@link IntArray} using {@code sun.misc.Unsafe}. Supports arrays with length beyond
 * Integer.MAX_VALUE.
 */
public class OffHeapIntArray extends OffHeapRegularNumberArray<IntArray> implements IntArray
{
    private final int defaultValue;

    public OffHeapIntArray( long length, int defaultValue, long base )
    {
        super( length, 2, base );
        this.defaultValue = defaultValue;
        clear();
    }

    @Override
    public int get( long index )
    {
        return UnsafeUtil.getInt( addressOf( index ) );
    }

    @Override
    public void set( long index, int value )
    {
        UnsafeUtil.putInt( addressOf( index ), value );
    }

    @Override
    public void clear()
    {
        if ( isByteUniform( defaultValue ) )
        {
            UnsafeUtil.setMemory( address, length << shift, (byte)defaultValue );
        }
        else
        {
            for ( long i = 0, adr = address; i < length; i++, adr += itemSize )
            {
                UnsafeUtil.putInt( adr, defaultValue );
            }
        }
    }

    @Override
    public void swap( long fromIndex, long toIndex, int numberOfEntries )
    {
        long fromAddress = addressOf( fromIndex );
        long toAddress = addressOf( toIndex );

        for ( int i = 0; i < numberOfEntries; i++, fromAddress += itemSize, toAddress += itemSize )
        {
            int fromValue = UnsafeUtil.getInt( fromAddress );
            UnsafeUtil.putInt( fromAddress, UnsafeUtil.getInt( toAddress ) );
            UnsafeUtil.putInt( toAddress, fromValue );
        }
    }
}
