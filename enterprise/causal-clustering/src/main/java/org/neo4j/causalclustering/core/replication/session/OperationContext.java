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
package org.neo4j.causalclustering.core.replication.session;

/** Context for operation. Used for acquirement and release. */
public class OperationContext
{
    private final GlobalSession globalSession;
    private final LocalOperationId localOperationId;

    private final LocalSession localSession;

    public OperationContext( GlobalSession globalSession, LocalOperationId localOperationId, LocalSession localSession )
    {
        this.globalSession = globalSession;
        this.localOperationId = localOperationId;
        this.localSession = localSession;
    }

    public GlobalSession globalSession()
    {
        return globalSession;
    }

    public LocalOperationId localOperationId()
    {
        return localOperationId;
    }

    protected LocalSession localSession()
    {
        return localSession;
    }

    @Override
    public String toString()
    {
        return "OperationContext{" +
               "globalSession=" + globalSession +
               ", localOperationId=" + localOperationId +
               '}';
    }
}
