/*
 * Copyright (c) 2002-2019 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.catchup;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import org.neo4j.causalclustering.catchup.storecopy.FileChunk;
import org.neo4j.causalclustering.catchup.storecopy.FileHeader;
import org.neo4j.causalclustering.catchup.storecopy.GetStoreIdResponse;
import org.neo4j.causalclustering.catchup.storecopy.StoreCopyFinishedResponse;
import org.neo4j.causalclustering.catchup.tx.TxPullResponse;
import org.neo4j.causalclustering.catchup.tx.TxStreamFinishedResponse;
import org.neo4j.causalclustering.core.state.snapshot.CoreSnapshot;

public class CatchUpResponseAdaptor<T> implements CatchUpResponseCallback<T>
{
    @Override
    public void onFileHeader( CompletableFuture<T> signal, FileHeader response )
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
    }

    @Override
    public boolean onFileContent( CompletableFuture<T> signal, FileChunk response ) throws IOException
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
        return false;
    }

    @Override
    public void onFileStreamingComplete( CompletableFuture<T> signal, StoreCopyFinishedResponse response )
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
    }

    @Override
    public void onTxPullResponse( CompletableFuture<T> signal, TxPullResponse response )
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
    }

    @Override
    public void onTxStreamFinishedResponse( CompletableFuture<T> signal, TxStreamFinishedResponse response )
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
    }

    @Override
    public void onGetStoreIdResponse( CompletableFuture<T> signal, GetStoreIdResponse response )
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
    }

    @Override
    public void onCoreSnapshot( CompletableFuture<T> signal, CoreSnapshot response )
    {
        signal.completeExceptionally( new CatchUpProtocolViolationException( "Unexpected response: %s", response ) );
    }
}
