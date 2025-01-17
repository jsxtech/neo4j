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
package org.neo4j.server.rest;

import org.neo4j.server.rest.repr.MappingRepresentation;
import org.neo4j.server.rest.repr.MappingSerializer;

public class CoreDatabaseAvailabilityDiscoveryRepresentation extends MappingRepresentation
{
    private static final String WRITABLE_KEY = "writable";
    private static final String DISCOVERY_REPRESENTATION_TYPE = "discovery";

    private final String basePath;
    private final String isWritableUri;

    public CoreDatabaseAvailabilityDiscoveryRepresentation( String basePath, String isWritableUri )
    {
        super( DISCOVERY_REPRESENTATION_TYPE );
        this.basePath = basePath;
        this.isWritableUri = isWritableUri;
    }

    @Override
    protected void serialize( MappingSerializer serializer )
    {
        serializer.putRelativeUri( WRITABLE_KEY, basePath + isWritableUri );
    }
}
