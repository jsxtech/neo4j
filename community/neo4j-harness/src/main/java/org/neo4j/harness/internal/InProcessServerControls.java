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
package org.neo4j.harness.internal;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.config.Configuration;
import org.neo4j.harness.ServerControls;
import org.neo4j.helpers.AdvertisedSocketAddress;
import org.neo4j.helpers.Exceptions;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.server.AbstractNeoServer;

import static org.neo4j.graphdb.factory.GraphDatabaseSettings.boltConnector;

public class InProcessServerControls implements ServerControls
{
    private final File serverFolder;
    private final AbstractNeoServer server;
    private final Closeable additionalClosable;

    public InProcessServerControls( File serverFolder, AbstractNeoServer server, Closeable additionalClosable )
    {
        this.serverFolder = serverFolder;
        this.server = server;
        this.additionalClosable = additionalClosable;
    }

    @Override
    public URI boltURI()
    {
        AdvertisedSocketAddress address = server.getConfig().get( boltConnector( "0" ).advertised_address );
        return URI.create( "bolt://" + address.getHostname() + ":" + address.getPort() );
    }

    @Override
    public URI httpURI()
    {
        return server.baseUri();
    }

    @Override
    public Optional<URI> httpsURI()
    {
        return server.httpsUri();
    }

    public void start()
    {
        this.server.start();
    }

    @Override
    public void close()
    {
        server.stop();
        try
        {
            additionalClosable.close();
        }
        catch ( Throwable e )
        {
            throw Exceptions.launderedException( e );
        }
        try
        {
            if( looksLikeMd5Hash( serverFolder.getName() ) )
            {
                FileUtils.deleteRecursively( serverFolder );
            }
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Failed to clean up test server directory.", e );
        }
    }

    private boolean looksLikeMd5Hash( String name )
    {
        // Pure paranoia, and a silly check - but this decreases the likelihood that we delete something that isn't
        // our randomly generated folder significantly.
        return name.length() == 32;
    }

    @Override
    public GraphDatabaseService graph()
    {
        return server.getDatabase().getGraph();
    }

    @Override
    public Configuration config()
    {
        return server.getConfig();
    }
}
