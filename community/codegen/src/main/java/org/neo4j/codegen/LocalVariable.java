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
package org.neo4j.codegen;

public class LocalVariable extends Expression
{
    private final TypeReference type;
    private final String name;
    private final int index;

    LocalVariable( TypeReference type, String name, int index )
    {
        this.type = type;
        this.name = name;
        this.index = index;
    }

    public TypeReference type()
    {
        return type;
    }

    public String name()
    {
        return name;
    }

    public int index()
    {
        return index;
    }

    @Override
    public void accept( ExpressionVisitor visitor )
    {
        visitor.load( this );
    }
}
