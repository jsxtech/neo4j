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
package org.neo4j.server.security.enterprise.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.neo4j.bolt.security.auth.AuthenticationException;
import org.neo4j.bolt.v1.messaging.message.FailureMessage;
import org.neo4j.bolt.v1.messaging.message.InitMessage;
import org.neo4j.bolt.v1.messaging.message.PullAllMessage;
import org.neo4j.bolt.v1.messaging.message.RecordMessage;
import org.neo4j.bolt.v1.messaging.message.ResponseMessage;
import org.neo4j.bolt.v1.messaging.message.RunMessage;
import org.neo4j.bolt.v1.messaging.message.SuccessMessage;
import org.neo4j.bolt.v1.transport.integration.Neo4jWithSocket;
import org.neo4j.bolt.v1.transport.integration.TransportTestUtil;
import org.neo4j.bolt.v1.transport.socket.client.SocketConnection;
import org.neo4j.bolt.v1.transport.socket.client.TransportConnection;
import org.neo4j.function.Factory;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.helpers.HostnamePort;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.security.AuthenticationResult;
import org.neo4j.kernel.api.security.SecurityContext;
import org.neo4j.kernel.enterprise.api.security.EnterpriseAuthManager;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.test.TestEnterpriseGraphDatabaseFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.neo4j.bolt.v1.messaging.message.ResetMessage.reset;
import static org.neo4j.helpers.collection.MapUtil.map;
import static org.neo4j.kernel.api.security.AuthToken.BASIC_SCHEME;
import static org.neo4j.kernel.api.security.AuthToken.CREDENTIALS;
import static org.neo4j.kernel.api.security.AuthToken.NATIVE_REALM;
import static org.neo4j.kernel.api.security.AuthToken.PRINCIPAL;
import static org.neo4j.kernel.api.security.AuthToken.REALM_KEY;
import static org.neo4j.kernel.api.security.AuthToken.SCHEME_KEY;
import static org.neo4j.kernel.api.security.AuthToken.newBasicAuthToken;

class BoltInteraction implements NeoInteractionLevel<BoltInteraction.BoltSubject>
{
    protected final HostnamePort address = new HostnamePort( "localhost:7687" );
    private final Factory<TransportConnection> connectionFactory = SocketConnection::new;
    private final Neo4jWithSocket server;
    private Map<String,BoltSubject> subjects = new HashMap<>();
    private EphemeralFileSystemAbstraction fileSystem;
    private EnterpriseAuthManager authManager;

    BoltInteraction( Map<String, String> config ) throws IOException
    {
        TestEnterpriseGraphDatabaseFactory factory = new TestEnterpriseGraphDatabaseFactory();
        fileSystem = new EphemeralFileSystemAbstraction();
        server = new Neo4jWithSocket( getClass(),
                factory,
                () -> fileSystem,
                settings -> {
                    settings.put( GraphDatabaseSettings.auth_enabled.name(), "true" );
                    settings.putAll( config );
                } );
        server.ensureDatabase( r -> {} );
        GraphDatabaseFacade db = (GraphDatabaseFacade) server.graphDatabaseService();
        authManager = db.getDependencyResolver().resolveDependency( EnterpriseAuthManager.class );
    }

    @Override
    public EnterpriseUserManager getLocalUserManager() throws Exception
    {
        if ( authManager instanceof EnterpriseAuthAndUserManager )
        {
            return ((EnterpriseAuthAndUserManager) authManager).getUserManager();
        }
        throw new Exception( "The used configuration does not have a user manager" );
    }

    @Override
    public GraphDatabaseFacade getLocalGraph()
    {
        return (GraphDatabaseFacade) server.graphDatabaseService();
    }

    @Override
    public FileSystemAbstraction fileSystem()
    {
        return fileSystem;
    }

    @Override
    public InternalTransaction beginLocalTransactionAsUser( BoltSubject subject, KernelTransaction.Type txType )
            throws Throwable
    {
        SecurityContext securityContext = authManager.login( newBasicAuthToken( subject.username, subject.password ) );
        return getLocalGraph().beginTransaction( txType, securityContext );
    }

    @Override
    public String executeQuery( BoltSubject subject, String call, Map<String,Object> params,
            Consumer<ResourceIterator<Map<String,Object>>> resultConsumer )
    {
        if ( params == null )
        {
            params = Collections.emptyMap();
        }
        try
        {
            subject.client.send( TransportTestUtil.chunk( RunMessage.run( call, params ), PullAllMessage.pullAll() ) );
            resultConsumer.accept( collectResults( subject.client ) );
            return "";
        }
        catch (Exception e)
        {
            return e.getMessage();
        }
    }

    @Override
    public BoltSubject login( String username, String password ) throws Exception
    {
        BoltSubject subject = subjects.get( username );
        if ( subject == null )
        {
            subject = new BoltSubject( connectionFactory.newInstance(), username, password );
            subjects.put( username, subject );
        }
        else
        {
            subject.client.disconnect();
            subject.client = connectionFactory.newInstance();
        }
        subject.client.connect( address ).send( TransportTestUtil.acceptedVersions( 1, 0, 0, 0 ) )
                .send( TransportTestUtil.chunk( InitMessage.init( "TestClient/1.1",
                        map( REALM_KEY, NATIVE_REALM, PRINCIPAL, username, CREDENTIALS, password,
                                SCHEME_KEY, BASIC_SCHEME ) ) ) );
        assertThat( subject.client, TransportTestUtil.eventuallyReceives( new byte[]{0, 0, 0, 1} ) );
        subject.setLoginResult( TransportTestUtil.receiveOneResponseMessage( subject.client ) );
        return subject;
    }

    @Override
    public void logout( BoltSubject subject ) throws Exception
    {
        subject.client.disconnect();
        subject.client = connectionFactory.newInstance();
    }

    @Override
    public void updateAuthToken( BoltSubject subject, String username, String password )
    {

    }

    @Override
    public String nameOf( BoltSubject subject )
    {
        return subject.username;
    }

    @Override
    public void tearDown() throws Throwable
    {
        for ( BoltSubject subject : subjects.values() )
        {
            subject.client.disconnect();
        }
        subjects.clear();
        server.graphDatabaseService().shutdown();
        fileSystem.shutdown();
    }

    @Override
    public void assertAuthenticated( BoltSubject subject )
    {
        assertTrue( "Should be authenticated", subject.isAuthenticated() );
    }

    @Override
    public void assertPasswordChangeRequired( BoltSubject subject )
    {
        assertTrue( "Should need to change password", subject.passwordChangeRequired() );
    }

    @Override
    public void assertInitFailed( BoltSubject subject )
    {
        assertFalse( "Should not be authenticated", subject.isAuthenticated() );
    }

    @Override
    public void assertSessionKilled( BoltSubject subject )
    {
        assertThat( subject.client, TransportTestUtil.eventuallyDisconnects() );
    }

    @Override
    public String getConnectionDetails()
    {
        return "bolt-session";
    }

    private static BoltResult collectResults( TransportConnection client ) throws Exception
    {
        ResponseMessage message = TransportTestUtil.receiveOneResponseMessage( client );
        List<String> fieldNames = new ArrayList<>();
        List<Map<String,Object>> result = new ArrayList<>();

        if ( message instanceof SuccessMessage )
        {
            Map<String,Object> metadata = ((SuccessMessage) message).meta();
            fieldNames = (List<String>) metadata.get( "fields" );
        }
        else if ( message instanceof FailureMessage )
        {
            FailureMessage failMessage = ((FailureMessage) message);
            // drain ignoredMessage, ack failure, get successMessage
            TransportTestUtil.receiveOneResponseMessage( client );
            client.send( TransportTestUtil.chunk( reset() ) );
            TransportTestUtil.receiveOneResponseMessage( client );
            throw new AuthenticationException( failMessage.status(), failMessage.message() );
        }

        do
        {
            message = TransportTestUtil.receiveOneResponseMessage( client );
            if ( message instanceof RecordMessage )
            {
                Object[] row = ((RecordMessage) message).record().fields();
                Map<String,Object> rowMap = new HashMap<>();
                for ( int i = 0; i < row.length; i++ )
                {
                    rowMap.put( fieldNames.get( i ), row[i] );
                }
                result.add( rowMap );
            }
        }
        while ( !(message instanceof SuccessMessage) && !(message instanceof FailureMessage) );

        if ( message instanceof FailureMessage )
        {
            FailureMessage failMessage = ((FailureMessage) message);
            // ack failure, get successMessage
            client.send( TransportTestUtil.chunk( reset() ) );
            TransportTestUtil.receiveOneResponseMessage( client );
            throw new AuthenticationException( failMessage.status(), failMessage.message() );
        }

        return new BoltResult( result );
    }

    static class BoltSubject
    {
        TransportConnection client;
        String username;
        String password;
        AuthenticationResult loginResult = AuthenticationResult.FAILURE;

        BoltSubject( TransportConnection client, String username, String password )
        {
            this.client = client;
            this.username = username;
            this.password = password;
        }

        void setLoginResult( ResponseMessage result )
        {
            if ( result instanceof SuccessMessage )
            {
                Map<String,Object> meta = ((SuccessMessage) result).meta();
                if ( meta.containsKey( "credentials_expired" ) && meta.get( "credentials_expired" ).equals( true ) )
                {
                    loginResult = AuthenticationResult.PASSWORD_CHANGE_REQUIRED;
                }
                else
                {
                    loginResult = AuthenticationResult.SUCCESS;
                }
            }
            else if ( result instanceof FailureMessage )
            {
                loginResult = AuthenticationResult.FAILURE;
                Status status = ((FailureMessage) result).status();
                if ( status.equals( Status.Security.AuthenticationRateLimit ) )
                {
                    loginResult = AuthenticationResult.TOO_MANY_ATTEMPTS;
                }
            }
        }

        boolean isAuthenticated()
        {
            return loginResult.equals( AuthenticationResult.SUCCESS );
        }

        boolean passwordChangeRequired()
        {
            return loginResult.equals( AuthenticationResult.PASSWORD_CHANGE_REQUIRED );
        }
    }

    static class BoltResult implements ResourceIterator<Map<String,Object>>
    {
        private int index = 0;
        private List<Map<String,Object>> data;

        BoltResult( List<Map<String,Object>> data )
        {
            this.data = data;
        }

        @Override
        public void close()
        {
            index = data.size();
        }

        @Override
        public boolean hasNext()
        {
            return index < data.size();
        }

        @Override
        public Map<String,Object> next()
        {
            Map<String,Object> row = data.get( index );
            index++;
            return row;
        }
    }
}
