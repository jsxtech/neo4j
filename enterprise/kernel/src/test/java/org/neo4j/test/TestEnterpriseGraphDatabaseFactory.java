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
package org.neo4j.test;

import java.io.File;
import java.util.Map;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactoryState;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.GraphDatabaseDependencies;
import org.neo4j.kernel.impl.enterprise.EnterpriseEditionModule;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.factory.Edition;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacadeFactory;
import org.neo4j.kernel.impl.factory.PlatformModule;
import org.neo4j.kernel.impl.logging.AbstractLogService;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.logging.SimpleLogService;
import org.neo4j.logging.LogProvider;

public class TestEnterpriseGraphDatabaseFactory extends TestGraphDatabaseFactory
{
    @Override
    protected GraphDatabaseBuilder.DatabaseCreator createDatabaseCreator( File storeDir,
                                                                          GraphDatabaseFactoryState state )
    {
        return new GraphDatabaseBuilder.DatabaseCreator() {

            @Override
            public GraphDatabaseService newDatabase( Map<String,String> config )
            {
                config.put( "unsupported.dbms.ephemeral", "false" );
                return new GraphDatabaseFacadeFactory( DatabaseInfo.ENTERPRISE, EnterpriseEditionModule::new )
                {
                    @Override
                    protected PlatformModule createPlatform( File storeDir, Map<String,String> params,
                            Dependencies dependencies, GraphDatabaseFacade graphDatabaseFacade )
                    {
                        return new PlatformModule( storeDir, params, databaseInfo, dependencies, graphDatabaseFacade ) {
                            @Override
                            protected LogService createLogService( LogProvider userLogProvider )
                            {
                                if ( state instanceof TestGraphDatabaseFactoryState )
                                {
                                    LogProvider logProvider = ((TestGraphDatabaseFactoryState) state).getInternalLogProvider();
                                    if ( logProvider != null )
                                    {
                                        return new SimpleLogService( logProvider, logProvider );
                                    }
                                }
                                return super.createLogService( userLogProvider );
                            }
                        };
                    }
                }.newFacade( storeDir, config,
                        GraphDatabaseDependencies.newDependencies( state.databaseDependencies() ) );
            }
        };
    }

    @Override
    protected GraphDatabaseBuilder.DatabaseCreator createImpermanentDatabaseCreator( final File storeDir,
            final TestGraphDatabaseFactoryState state )
    {
        return new GraphDatabaseBuilder.DatabaseCreator()
        {
            @Override
            @SuppressWarnings( "deprecation" )
            public GraphDatabaseService newDatabase( Map<String,String> config )
            {
                return new GraphDatabaseFacadeFactory( DatabaseInfo.ENTERPRISE, EnterpriseEditionModule::new )
                {
                    @Override
                    protected PlatformModule createPlatform( File storeDir, Map<String,String> params,
                            Dependencies dependencies,
                            GraphDatabaseFacade graphDatabaseFacade )
                    {
                        return new ImpermanentGraphDatabase.ImpermanentPlatformModule( storeDir, params, databaseInfo,
                                dependencies, graphDatabaseFacade )
                        {
                            @Override
                            protected FileSystemAbstraction createFileSystemAbstraction()
                            {
                                FileSystemAbstraction fs = state.getFileSystem();
                                if ( fs != null )
                                {
                                    return fs;
                                }
                                else
                                {
                                    return super.createFileSystemAbstraction();
                                }
                            }

                            @Override
                            protected LogService createLogService( LogProvider logProvider )
                            {
                                final LogProvider internalLogProvider = state.getInternalLogProvider();
                                if ( internalLogProvider == null )
                                {
                                    return super.createLogService( logProvider );
                                }

                                final LogProvider userLogProvider = state.databaseDependencies().userLogProvider();
                                return new AbstractLogService()
                                {
                                    @Override
                                    public LogProvider getUserLogProvider()
                                    {
                                        return userLogProvider;
                                    }

                                    @Override
                                    public LogProvider getInternalLogProvider()
                                    {
                                        return internalLogProvider;
                                    }
                                };
                            }

                        };
                    }
                }.newFacade( storeDir, config,
                        GraphDatabaseDependencies.newDependencies( state.databaseDependencies() ) );
            }
        };
    }

    @Override
    public String getEdition()
    {
        return Edition.enterprise.toString();
    }
}
