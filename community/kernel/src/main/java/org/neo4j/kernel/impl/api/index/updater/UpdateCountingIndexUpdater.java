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
package org.neo4j.kernel.impl.api.index.updater;

import java.io.IOException;

import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.impl.api.index.IndexStoreView;

public class UpdateCountingIndexUpdater implements IndexUpdater
{
    private final IndexStoreView storeView;
    private final IndexDescriptor descriptor;
    private final IndexUpdater delegate;
    private long updates;

    public UpdateCountingIndexUpdater( IndexStoreView storeView, IndexDescriptor descriptor, IndexUpdater delegate )
    {
        this.storeView = storeView;
        this.descriptor = descriptor;
        this.delegate = delegate;
    }

    @Override
    public void process( NodePropertyUpdate update ) throws IOException, IndexEntryConflictException
    {
        delegate.process( update );
        updates++;
    }

    @Override
    public void close() throws IOException, IndexEntryConflictException
    {
        delegate.close();
        storeView.incrementIndexUpdates( descriptor, updates );
    }

    @Override
    public void remove( PrimitiveLongSet nodeIds ) throws IOException
    {
        delegate.remove( nodeIds );
        updates += nodeIds.size();
    }
}
