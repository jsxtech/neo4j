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
package org.neo4j.bolt.v1.messaging.message;

import org.neo4j.bolt.v1.messaging.BoltRequestMessageHandler;

public class PullAllMessage implements RequestMessage
{
    private static PullAllMessage INSTANCE = new PullAllMessage();

    public static PullAllMessage pullAll()
    {
        return INSTANCE;
    }

    private PullAllMessage()
    {
    }

    @Override
    public <E extends Exception> void dispatch( BoltRequestMessageHandler<E> consumer ) throws E
    {
        consumer.onPullAll();
    }

    @Override
    public boolean equals( Object obj )
    {
        return obj instanceof PullAllMessage;
    }

    @Override
    public int hashCode()
    {
        return 1;
    }

    @Override
    public String toString()
    {
        return "PullAllMessage{}";
    }
}
