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
package org.neo4j.cluster.com.message;

import java.util.ArrayList;
import java.util.List;

import org.neo4j.helpers.collection.Iterables;

public class TrackingMessageHolder implements MessageHolder
{
    private final List<Message> messages = new ArrayList<>();

    @Override
    public void offer( Message<? extends MessageType> message )
    {
        messages.add( message );
    }

    public <T extends MessageType> Message<T> single()
    {
        return Iterables.single( messages );
    }

    public <T extends MessageType> Message<T> first()
    {
        return Iterables.first( messages );
    }
}
