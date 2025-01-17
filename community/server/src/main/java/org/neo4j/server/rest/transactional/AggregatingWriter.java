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
package org.neo4j.server.rest.transactional;

import org.codehaus.jackson.JsonGenerator;

import java.io.IOException;

import org.neo4j.graphdb.Result;

class AggregatingWriter implements ResultDataContentWriter
{
    private final ResultDataContentWriter[] writers;

    public AggregatingWriter( ResultDataContentWriter[] writers )
    {
        this.writers = writers;
    }

    @Override
    public void write( JsonGenerator out, Iterable<String> columns, Result.ResultRow row,
            TransactionStateChecker txStateChecker ) throws IOException
    {
        for ( ResultDataContentWriter writer : writers )
        {
            writer.write( out, columns, row, txStateChecker );
        }
    }
}
