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
package org.neo4j.causalclustering.readreplica;

import java.io.File;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.causalclustering.discovery.DiscoveryServiceFactory;
import org.neo4j.causalclustering.discovery.HazelcastDiscoveryServiceFactory;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.factory.EditionModule;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacadeFactory;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacadeFactory.Dependencies;
import org.neo4j.kernel.impl.factory.PlatformModule;
import org.neo4j.kernel.impl.util.CustomIOConfigValidator;

public class ReadReplicaGraphDatabase extends GraphDatabaseFacade
{
    public static final String CUSTOM_IO_EXCEPTION_MESSAGE =
            "Read replica mode is not allowed with custom IO integrations";

    public ReadReplicaGraphDatabase( File storeDir, Map<String,String> params, Dependencies dependencies )
    {
        this( storeDir, params, dependencies, new HazelcastDiscoveryServiceFactory() );
    }

    public ReadReplicaGraphDatabase( File storeDir, Map<String,String> params, Dependencies dependencies,
            DiscoveryServiceFactory discoveryServiceFactory )
    {
        CustomIOConfigValidator.assertCustomIOConfigNotUsed( new Config( params ), CUSTOM_IO_EXCEPTION_MESSAGE );
        Function<PlatformModule,EditionModule> factory =
                ( platformModule ) -> new EnterpriseReadReplicaEditionModule( platformModule, discoveryServiceFactory );
        new GraphDatabaseFacadeFactory( DatabaseInfo.READ_REPLICA, factory ).initFacade( storeDir, params, dependencies, this );
    }
}
