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

import java.io.IOException;
import javax.ws.rs.core.MediaType;

import org.junit.After;
import org.junit.Test;

import org.neo4j.helpers.ListenSocketAddress;
import org.neo4j.server.configuration.ServerSettings;
import org.neo4j.server.rest.JaxRsResponse;
import org.neo4j.server.rest.RestRequest;
import org.neo4j.server.scripting.javascript.GlobalJavascriptInitializer;
import org.neo4j.test.server.ExclusiveServerTestBase;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import static org.neo4j.server.helpers.CommunityServerBuilder.server;
import static org.neo4j.test.server.HTTP.POST;

public class ServerConfigIT extends ExclusiveServerTestBase
{
    private CommunityNeoServer server;

    @After
    public void stopTheServer()
    {
        server.stop();
    }

    @Test
    public void shouldPickUpAddressFromConfig() throws Exception
    {
        ListenSocketAddress nonDefaultAddress = new ListenSocketAddress( "0.0.0.0", 4321 );
        server = server().onAddress( nonDefaultAddress )
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();
        server.start();

        assertEquals( nonDefaultAddress, server.getAddress() );

        JaxRsResponse response = new RestRequest( server.baseUri() ).get();

        assertThat( response.getStatus(), is( 200 ) );
        response.close();
    }

    @Test
    public void shouldPickupRelativeUrisForMangementApiAndRestApi() throws IOException
    {
        String dataUri = "/a/different/data/uri/";
        String managementUri = "/a/different/management/uri/";

        server = server().withRelativeRestApiUriPath( dataUri )
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .withRelativeManagementApiUriPath( managementUri )
                .build();
        server.start();

        JaxRsResponse response = new RestRequest().get( "http://localhost:7474" + dataUri,
                MediaType.TEXT_HTML_TYPE );
        assertEquals( 200, response.getStatus() );

        response = new RestRequest().get( "http://localhost:7474" + managementUri );
        assertEquals( 200, response.getStatus() );
        response.close();
    }

    @Test
    public void shouldGenerateWADLWhenExplicitlyEnabledInConfig() throws IOException
    {
        server = server().withProperty( ServerSettings.wadl_enabled.name(), "true" )
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();
        server.start();
        JaxRsResponse response = new RestRequest().get( "http://localhost:7474/application.wadl",
                MediaType.WILDCARD_TYPE );

        assertEquals( 200, response.getStatus() );
        assertEquals( "application/vnd.sun.wadl+xml", response.getHeaders().get( "Content-Type" ).iterator().next() );
        assertThat( response.getEntity(), containsString( "<application xmlns=\"http://wadl.dev.java" +
                                                          ".net/2009/02\">" ) );
    }

    @Test
    public void shouldNotGenerateWADLWhenNotExplicitlyEnabledInConfig() throws IOException
    {
        server = server()
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();
        server.start();
        JaxRsResponse response = new RestRequest().get( "http://localhost:7474/application.wadl",
                MediaType.WILDCARD_TYPE );

        assertEquals( 404, response.getStatus() );
    }

    @Test
    public void shouldNotGenerateWADLWhenExplicitlyDisabledInConfig() throws IOException
    {
        server = server().withProperty( ServerSettings.wadl_enabled.name(), "false" )
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();
        server.start();
        JaxRsResponse response = new RestRequest().get( "http://localhost:7474/application.wadl",
                MediaType.WILDCARD_TYPE );

        assertEquals( 404, response.getStatus() );
    }

    @Test
    public void shouldEnablConsoleServiceByDefault() throws IOException
    {
        // Given
        server = server().usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() ).build();
        server.start();

        // When & then
        assertEquals( 200, new RestRequest().get( "http://localhost:7474/db/manage/server/console" ).getStatus() );
    }

    @Test
    public void shouldDisableConsoleServiceWhenAskedTo() throws IOException
    {
        // Given
        server = server().withProperty( ServerSettings.console_module_enabled.name(), "false" )
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();
        server.start();

        // When & then
        assertEquals( 404, new RestRequest().get( "http://localhost:7474/db/manage/server/console" ).getStatus() );
    }

    @Test
    public void shouldHaveSandboxingEnabledByDefault() throws Exception
    {
        // Given
        server = server()
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();
        server.start();
        String node = POST( server.baseUri().toASCIIString() + "db/data/node" ).location();

        // When
        JaxRsResponse response = new RestRequest().post( node + "/traverse/node", "{\n" +
                "  \"order\" : \"breadth_first\",\n" +
                "  \"return_filter\" : {\n" +
                "    \"body\" : \"position.getClass().getClassLoader()\",\n" +
                "    \"language\" : \"javascript\"\n" +
                "  },\n" +
                "  \"prune_evaluator\" : {\n" +
                "    \"body\" : \"position.getClass().getClassLoader()\",\n" +
                "    \"language\" : \"javascript\"\n" +
                "  },\n" +
                "  \"uniqueness\" : \"node_global\",\n" +
                "  \"relationships\" : [ {\n" +
                "    \"direction\" : \"all\",\n" +
                "    \"type\" : \"knows\"\n" +
                "  }, {\n" +
                "    \"direction\" : \"all\",\n" +
                "    \"type\" : \"loves\"\n" +
                "  } ],\n" +
                "  \"max_depth\" : 3\n" +
                "}", MediaType.APPLICATION_JSON_TYPE );

        // Then
        assertEquals( 400, response.getStatus() );
    }

    /*
     * We can't actually test that disabling sandboxing works, because of the set-once global nature of Rhino
     * security. Instead, we test here that changing it triggers the expected exception, letting us know that
     * the code that *would* have set it to disabled realizes it has already been set to sandboxed.
     *
     * This at least lets us know that the configuration attribute gets picked up and used.
     */
    @Test(expected = RuntimeException.class)
    public void shouldBeAbleToDisableSandboxing() throws Exception
    {
        // NOTE: This has to be initialized to sandboxed, because it can only be initialized once per JVM session,
        // and all other tests depend on it being sandboxed.
        GlobalJavascriptInitializer.initialize( GlobalJavascriptInitializer.Mode.SANDBOXED );

        server = server().withProperty( ServerSettings.script_sandboxing_enabled.name(), "false" )
                .usingDataDir( folder.directory( name.getMethodName() ).getAbsolutePath() )
                .build();

        // When
        server.start();
    }
}
