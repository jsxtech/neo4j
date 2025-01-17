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
package org.neo4j.helpers.collection;

import java.util.Iterator;

/**
 * Wraps an {@link Iterable} so that it returns items of another type. The
 * iteration is done lazily.
 *
 * @param <T> the type of items to return
 * @param <U> the type of items to wrap/convert from
 */
public abstract class IterableWrapper<T, U> implements Iterable<T>
{
    private Iterable<U> source;

    public IterableWrapper( Iterable<U> iterableToWrap )
    {
        this.source = iterableToWrap;
    }

    protected abstract T underlyingObjectToObject( U object );

    public Iterator<T> iterator()
    {
        return new MyIteratorWrapper( source.iterator() );
    }

    private class MyIteratorWrapper extends IteratorWrapper<T, U>
    {
        public MyIteratorWrapper( Iterator<U> iteratorToWrap )
        {
            super( iteratorToWrap );
        }

        @Override
        protected T underlyingObjectToObject( U object )
        {
            return IterableWrapper.this.underlyingObjectToObject( object );
        }
    }
}
