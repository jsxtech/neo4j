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
package org.neo4j.com.storecopy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public interface StoreWriter extends Closeable
{
    /**
     * Pipe the data from the given {@link ReadableByteChannel} to a location given by the {@code path}, using the
     * given {@code temporaryBuffer} for buffering if necessary.
     * The {@code hasData} is an effect of the block format not supporting a zero length blocks, whereas a neostore
     * file may actually be 0 bytes we'll have to keep track of that special case.
     * The {@code requiredElementAlignment} parameter specifies the size in bytes to which the transferred elements
     * should be aligned. For record store files, this is the record size. For files that have no special alignment
     * requirements, you should use the value {@code 1} to signify that any alignment will do.
     */
    long write( String path, ReadableByteChannel data, ByteBuffer temporaryBuffer, boolean hasData,
                int requiredElementAlignment ) throws IOException;

    @Override
    void close();
}
