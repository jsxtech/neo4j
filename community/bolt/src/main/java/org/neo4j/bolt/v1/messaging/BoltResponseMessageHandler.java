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
package org.neo4j.bolt.v1.messaging;

import org.neo4j.bolt.v1.runtime.spi.Record;
import org.neo4j.kernel.api.exceptions.Status;

import java.util.Map;

/**
 * Interface defining simple handler methods for each defined
 * Bolt response message.
 *
 * @param <E> an exception that may be thrown by each handler method
 */
public interface BoltResponseMessageHandler<E extends Exception>
{
    void onSuccess( Map<String, Object> metadata ) throws E;

    void onRecord( Record item ) throws E;

    void onIgnored() throws E;

    void onFailure( Status status, String message ) throws E;

    default void onFatal( Status status, String message ) throws E { onFailure( status, message ); }
}
