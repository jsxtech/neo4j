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
package org.neo4j.kernel.api.index;

import java.io.IOException;

import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;

/**
 * IndexUpdaters are responsible for updating indexes during the commit process. There is one new instance handling
 * each commit, created from {@link org.neo4j.kernel.api.index.IndexAccessor}.
 *
 * {@link #process(NodePropertyUpdate)} is called for each entry, wherein the actual updates are applied.
 *
 * Each IndexUpdater is not thread-safe, and is assumed to be instantiated per transaction.
 */
public interface IndexUpdater extends AutoCloseable
{
    void process( NodePropertyUpdate update ) throws IOException, IndexEntryConflictException;

    @Override
    void close() throws IOException, IndexEntryConflictException;

    void remove( PrimitiveLongSet nodeIds ) throws IOException;
}
