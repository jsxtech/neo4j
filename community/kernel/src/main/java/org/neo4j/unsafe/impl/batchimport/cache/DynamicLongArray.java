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

/**
 * Dynamically growing {@link LongArray}. Is given a chunk size and chunks are added as higher and higher
 * items are requested.
 *
 * @see NumberArrayFactory#newDynamicLongArray(long, long)
 */
public class DynamicLongArray extends DynamicNumberArray<LongArray> implements LongArray
{
    private final long defaultValue;

    public DynamicLongArray( NumberArrayFactory factory, long chunkSize, long defaultValue )
    {
        super( factory, chunkSize, new LongArray[0] );
        this.defaultValue = defaultValue;
    }

    @Override
    public long get( long index )
    {
        LongArray chunk = chunkOrNullAt( index );
        return chunk != null ? chunk.get( index ) : defaultValue;
    }

    @Override
    public void set( long index, long value )
    {
        at( index ).set( index, value );
    }

    @Override
    public void swap( long fromIndex, long toIndex, int numberOfEntries )
    {
        // Let's just do this the stupid way. There's room for optimization here
        for ( int i = 0; i < numberOfEntries; i++ )
        {
            long intermediary = get( fromIndex+i );
            set( fromIndex+i, get( toIndex+i ) );
            set( toIndex+i, intermediary );
        }
    }

    @Override
    protected LongArray addChunk( long chunkSize, long base )
    {
        return factory.newLongArray( chunkSize, defaultValue, base );
    }
}
