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
package org.neo4j.server;

import java.util.List;
import java.util.Map;

import org.neo4j.dbms.DatabaseManagementSystemSettings;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.kernel.GraphDatabaseDependencies;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.logging.LogProvider;
import org.neo4j.server.configuration.ServerSettings;

import static java.util.Arrays.asList;

public class CommunityBootstrapper extends ServerBootstrapper
{
    public static final List<Class<?>> settingsClasses =
            asList( ServerSettings.class, GraphDatabaseSettings.class, DatabaseManagementSystemSettings.class );

    @Override
    protected NeoServer createNeoServer( Config config, GraphDatabaseDependencies dependencies,
                                         LogProvider logProvider )
    {
        return new CommunityNeoServer( config, dependencies, logProvider );
    }

    @Override
    protected Iterable<Class<?>> settingsClasses( Map<String, String> settings )
    {
        return settingsClasses;
    }

}
