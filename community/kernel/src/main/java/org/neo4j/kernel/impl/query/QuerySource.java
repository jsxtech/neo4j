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
package org.neo4j.kernel.impl.query;

public class QuerySource
{
    private final String[] parts;

    public static final QuerySource UNKNOWN = new QuerySource( "<unknown>");

    public QuerySource( String ... parts )
    {
        this.parts = parts;
    }

    public QuerySource append( String newPart )
    {
        String[] newParts = new String[parts.length + 1];
        System.arraycopy( parts, 0, newParts, 0, parts.length );
        newParts[parts.length] = newPart;
        return new QuerySource( newParts );
    }

    @Override
    public String toString()
    {
        return toString( "\t" );
    }

    public String toString( String sep )
    {
        return String.join( sep, parts );
    }
}
