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
package org.neo4j.causalclustering.scenarios;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;

import org.neo4j.causalclustering.catchup.tx.FileCopyMonitor;
import org.neo4j.causalclustering.core.CausalClusteringSettings;
import org.neo4j.causalclustering.core.CoreGraphDatabase;
import org.neo4j.causalclustering.core.consensus.log.segmented.FileNames;
import org.neo4j.causalclustering.core.consensus.roles.Role;
import org.neo4j.causalclustering.discovery.Cluster;
import org.neo4j.causalclustering.discovery.ClusterMember;
import org.neo4j.causalclustering.discovery.CoreClusterMember;
import org.neo4j.causalclustering.discovery.HazelcastDiscoveryServiceFactory;
import org.neo4j.causalclustering.discovery.ReadReplica;
import org.neo4j.causalclustering.readreplica.ReadReplicaGraphDatabase;
import org.neo4j.function.ThrowingSupplier;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.security.WriteOperationsNotAllowedException;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.impl.api.scan.LabelScanStoreProvider;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.pagecache.StandalonePageCacheFactory;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.format.highlimit.HighLimit;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_0;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFiles;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.util.UnsatisfiedDependencyException;
import org.neo4j.kernel.lifecycle.LifecycleException;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.Log;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.causalclustering.ClusterRule;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.neo4j.causalclustering.core.consensus.log.RaftLog.RAFT_LOG_DIRECTORY_NAME;
import static org.neo4j.com.storecopy.StoreUtil.TEMP_COPY_DIRECTORY_NAME;
import static org.neo4j.function.Predicates.awaitEx;
import static org.neo4j.helpers.collection.Iterables.count;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.kernel.impl.store.MetaDataStore.Position.TIME;
import static org.neo4j.test.assertion.Assert.assertEventually;

public class ReadReplicaReplicationIT
{
    @Rule
    public final ClusterRule clusterRule =
            new ClusterRule( getClass() ).withNumberOfCoreMembers( 3 ).withNumberOfReadReplicas( 1 )
                    .withSharedCoreParam( CausalClusteringSettings.cluster_topology_refresh, "5s" )
                    .withDiscoveryServiceFactory( new HazelcastDiscoveryServiceFactory() );

    @Test
    public void shouldNotBeAbleToWriteToReadReplica() throws Exception
    {
        // given
        Cluster cluster = clusterRule.startCluster();

        ReadReplicaGraphDatabase readReplica = cluster.findAnyReadReplica().database();

        // when
        try ( Transaction tx = readReplica.beginTx() )
        {
            Node node = readReplica.createNode();
            node.setProperty( "foobar", "baz_bat" );
            node.addLabel( Label.label( "Foo" ) );
            tx.success();
            fail( "should have thrown" );
        }
        catch ( WriteOperationsNotAllowedException e )
        {
            // then all good
        }
    }

    @Test
    public void allServersBecomeAvailable() throws Exception
    {
        // given
        Cluster cluster = clusterRule.startCluster();

        // then
        for ( final ReadReplica readReplica : cluster.readReplicas() )
        {
            ThrowingSupplier<Boolean,Exception> availability = () -> readReplica.database().isAvailable( 0 );
            assertEventually( "read replica becomes available", availability, is( true ), 10, SECONDS );
        }
    }

    @Test
    public void shouldEventuallyPullTransactionDownToAllReadReplicas() throws Exception
    {
        // given
        Cluster cluster = clusterRule.withNumberOfReadReplicas( 0 ).startCluster();
        int nodesBeforeReadReplicaStarts = 1;

        cluster.coreTx( ( db, tx ) ->
        {
            db.schema().constraintFor( Label.label( "Foo" ) ).assertPropertyIsUnique( "foobar" ).create();
            tx.success();
        } );

        // when
        for ( int i = 0; i < 100; i++ )
        {
            cluster.coreTx( ( db, tx ) ->
            {
                createData( db, nodesBeforeReadReplicaStarts );
                tx.success();
            } );
        }

        AtomicBoolean labelScanStoreCorrectlyPlaced = new AtomicBoolean( false );
        Monitors monitors = new Monitors();
        ReadReplica rr = cluster.addReadReplicaWithIdAndMonitors( 0, monitors );

        File labelScanStore = LabelScanStoreProvider.getStoreDirectory( new File( rr.storeDir(), TEMP_COPY_DIRECTORY_NAME ) );

        monitors.addMonitorListener( (FileCopyMonitor) file ->
        {
            if ( file.getParent().contains( labelScanStore.getPath() ) )
            {
                labelScanStoreCorrectlyPlaced.set( true );
            }
        } );

        rr.start();

        for ( int i = 0; i < 100; i++ )
        {
            cluster.coreTx( ( db, tx ) ->
            {
                createData( db, nodesBeforeReadReplicaStarts );
                tx.success();
            } );
        }

        // then
        for ( final ReadReplica server : cluster.readReplicas() )
        {
            GraphDatabaseService readReplica = server.database();
            try ( Transaction tx = readReplica.beginTx() )
            {
                ThrowingSupplier<Long,Exception> nodeCount = () -> count( readReplica.getAllNodes() );
                assertEventually( "node to appear on read replica", nodeCount, is( 200L ) , 1, MINUTES );

                for ( Node node : readReplica.getAllNodes() )
                {
                    assertThat( node.getProperty( "foobar" ).toString(), startsWith( "baz_bat" ) );
                }

                tx.success();
            }
        }

        assertTrue( labelScanStoreCorrectlyPlaced.get() );
    }

    @Test
    public void shouldShutdownRatherThanPullUpdatesFromCoreMemberWithDifferentStoreIdIfLocalStoreIsNonEmpty()
            throws Exception
    {
        Cluster cluster = clusterRule.withNumberOfReadReplicas( 0 ).startCluster();

        cluster.coreTx( createSomeData );

        CoreClusterMember follower = cluster.awaitCoreMemberWithRole( Role.FOLLOWER, 2, TimeUnit.SECONDS );
        // Shutdown server before copying its data, because Windows can't copy open files.
        follower.shutdown();

        ReadReplica readReplica = cluster.addReadReplicaWithId( 4 );
        putSomeDataWithDifferentStoreId( readReplica.storeDir(), follower.storeDir() );
        follower.start();

        try
        {
            readReplica.start();
            fail( "Should have failed to start" );
        }
        catch ( RuntimeException required )
        {
            // Lifecycle should throw exception, server should not start.
            assertThat( required.getCause(), instanceOf( LifecycleException.class ) );
            assertThat( required.getCause().getCause(), instanceOf( Exception.class ) );
            assertThat( required.getCause().getCause().getMessage(),
                    containsString( "This read replica cannot join the cluster. " +
                            "The local database is not empty and has a mismatching storeId:" ) );
        }
    }

    @Test
    public void aReadReplicShouldBeAbleToRejoinTheCluster() throws Exception
    {
        int readReplicaId = 4;
        Cluster cluster = clusterRule.withNumberOfReadReplicas( 0 ).startCluster();

        cluster.coreTx( createSomeData );

        cluster.addReadReplicaWithId( readReplicaId ).start();

        // let's spend some time by adding more data
        cluster.coreTx( createSomeData );

        awaitEx( () -> readReplicasUpToDateAsTheLeader( cluster.awaitLeader(), cluster.readReplicas() ), 1, TimeUnit.MINUTES );
        cluster.removeReadReplicaWithMemberId( readReplicaId );

        // let's spend some time by adding more data
        cluster.coreTx( createSomeData );

        cluster.addReadReplicaWithId( readReplicaId ).start();

        awaitEx( () -> readReplicasUpToDateAsTheLeader( cluster.awaitLeader(), cluster.readReplicas() ), 1, TimeUnit.MINUTES );

        List<File> coreStoreDirs =
                cluster.coreMembers().stream().map( CoreClusterMember::storeDir ).collect( toList() );
        List<File> readReplicaStoreDirs =
                cluster.readReplicas().stream().map( ReadReplica::storeDir ).collect( toList() );

        cluster.shutdown();

        Set<DbRepresentation> dbs = coreStoreDirs.stream().map( DbRepresentation::of ).collect( toSet() );
        dbs.addAll( readReplicaStoreDirs.stream().map( DbRepresentation::of ).collect( toSet() ) );
        assertEquals( 1, dbs.size() );
    }

    @Test
    public void readReplicasShouldRestartIfTheWholeClusterIsRestarted() throws Exception
    {
        // given
        Cluster cluster = clusterRule.startCluster();

        // when
        cluster.shutdown();
        cluster.start();

        // then
        for ( final ReadReplica readReplica : cluster.readReplicas() )
        {
            ThrowingSupplier<Boolean,Exception> availability = () -> readReplica.database().isAvailable( 0 );
            assertEventually( "read replica becomes available", availability, is( true ), 10, SECONDS );
        }
    }

    @Test
    public void shouldBeAbleToDownloadANewStoreAfterPruning() throws Exception
    {
        // given
        Map<String,String> params = stringMap( GraphDatabaseSettings.keep_logical_logs.name(), "keep_none",
                GraphDatabaseSettings.logical_log_rotation_threshold.name(), "1M",
                GraphDatabaseSettings.check_point_interval_time.name(), "100ms" );

        Cluster cluster = clusterRule.withSharedCoreParams( params ).startCluster();

        cluster.coreTx( ( db, tx ) ->
        {
            createData( db, 10 );
            tx.success();
        } );

        awaitEx( () -> readReplicasUpToDateAsTheLeader( cluster.awaitLeader(), cluster.readReplicas() ), 1, TimeUnit.MINUTES );

        ReadReplica readReplica = cluster.getReadReplicaById( 0 );
        long highestReadReplicaLogVersion = physicalLogFiles( readReplica ).getHighestLogVersion();

        // when
        readReplica.shutdown();

        CoreClusterMember core;
        do
        {
            core = cluster.coreTx( ( db, tx ) ->
            {
                createData( db, 1_000 );
                tx.success();
            } );

        }
        while ( physicalLogFiles( core ).getLowestLogVersion() <= highestReadReplicaLogVersion );

        readReplica.start();

        // then
        awaitEx( () -> readReplicasUpToDateAsTheLeader( cluster.awaitLeader(), cluster.readReplicas() ), 1, TimeUnit.MINUTES );

        assertEventually( "The read replica has the same data as the core members",
                () -> DbRepresentation.of( readReplica.database() ),
                equalTo( DbRepresentation.of( cluster.awaitLeader().database() ) ), 10, TimeUnit.SECONDS );
    }
    @Test
    public void shouldBeAbleToPullTxAfterHavingDownloadedANewStoreAfterPruning() throws Exception
    {
        // given
        Map<String,String> params = stringMap( GraphDatabaseSettings.keep_logical_logs.name(), "keep_none",
                GraphDatabaseSettings.logical_log_rotation_threshold.name(), "1M",
                GraphDatabaseSettings.check_point_interval_time.name(), "100ms" );

        Cluster cluster = clusterRule.withSharedCoreParams( params ).startCluster();

        cluster.coreTx( ( db, tx ) ->
        {
            createData( db, 10 );
            tx.success();
        } );

        awaitEx( () -> readReplicasUpToDateAsTheLeader( cluster.awaitLeader(), cluster.readReplicas() ), 1, TimeUnit.MINUTES );

        ReadReplica readReplica = cluster.getReadReplicaById( 0 );
        long highestReadReplicaLogVersion = physicalLogFiles( readReplica ).getHighestLogVersion();

        readReplica.shutdown();

        CoreClusterMember core;
        do
        {
            core = cluster.coreTx( ( db, tx ) ->
            {
                createData( db, 1_000 );
                tx.success();
            } );

        }
        while ( physicalLogFiles( core ).getLowestLogVersion() <= highestReadReplicaLogVersion );

        readReplica.start();

        awaitEx( () -> readReplicasUpToDateAsTheLeader( cluster.awaitLeader(), cluster.readReplicas() ), 1, TimeUnit.MINUTES );

        // when
        cluster.coreTx( (db, tx) -> {
            createData( db );
            tx.success();
        } );

        // then
        assertEventually( "The read replica has the same data as the core members",
                () -> DbRepresentation.of( readReplica.database() ),
                equalTo( DbRepresentation.of( cluster.awaitLeader().database() ) ), 10, TimeUnit.SECONDS );
    }

    private PhysicalLogFiles physicalLogFiles( ClusterMember clusterMember )
    {
        return clusterMember.database().getDependencyResolver().resolveDependency( PhysicalLogFiles.class );
    }

    private boolean readReplicasUpToDateAsTheLeader( CoreClusterMember leader,
            Collection<ReadReplica> readReplicas )
    {
        long leaderTxId = lastClosedTransactionId( true, leader.database() );
        return readReplicas.stream().map( ReadReplica::database )
                .map( db -> lastClosedTransactionId( false, db ) )
                .reduce( true, ( acc, txId ) -> acc && txId == leaderTxId, Boolean::logicalAnd );
    }

    private void putSomeDataWithDifferentStoreId( File storeDir, File coreStoreDir ) throws IOException
    {
        FileUtils.copyRecursively( coreStoreDir, storeDir );
        changeStoreId( storeDir );
    }

    private void changeStoreId( File storeDir ) throws IOException
    {
        File neoStoreFile = new File( storeDir, MetaDataStore.DEFAULT_NAME );
        try ( PageCache pageCache = StandalonePageCacheFactory.createPageCache( new DefaultFileSystemAbstraction() ) )
        {
            MetaDataStore.setRecord( pageCache, neoStoreFile, TIME, System.currentTimeMillis() );
        }
    }

    private long lastClosedTransactionId( boolean fail, GraphDatabaseFacade db )
    {
        try
        {
            return db.getDependencyResolver().resolveDependency( TransactionIdStore.class )
                    .getLastClosedTransactionId();
        }
        catch ( IllegalStateException  | UnsatisfiedDependencyException /* db is shutdown or not available */ ex )
        {
            if ( !fail )
            {
                // the db is down we'll try again...
                return -1;
            }
            else
            {
                throw ex;
            }
        }
    }

    @Test
    public void shouldThrowExceptionIfReadReplicaRecordFormatDiffersToCoreRecordFormat() throws Exception
    {
        // given
        Cluster cluster = clusterRule.withNumberOfReadReplicas( 0 ).withRecordFormat( HighLimit.NAME ).startCluster();

        // when
        cluster.coreTx( createSomeData );

        try
        {
            cluster.addReadReplicaWithIdAndRecordFormat( 0, StandardV3_0.NAME ).start();
            fail( "starting read replica with '" + StandardV3_0.NAME + "' format should have failed" );
        }
        catch ( Exception e )
        {
            assertThat( e.getCause().getCause().getMessage(),
                    containsString( "Failed to start database with copied store" ) );
        }
    }

    @Test
    public void shouldBeAbleToCopyStoresFromCoreToReadReplica() throws Exception
    {
        // given
        Map<String,String> params = stringMap( CausalClusteringSettings.raft_log_rotation_size.name(), "1k",
                CausalClusteringSettings.raft_log_pruning_frequency.name(), "500ms",
                CausalClusteringSettings.state_machine_flush_window_size.name(), "1",
                CausalClusteringSettings.raft_log_pruning_strategy.name(), "1 entries" );
        Cluster cluster = clusterRule.withNumberOfReadReplicas( 0 ).withSharedCoreParams( params )
                .withRecordFormat( HighLimit.NAME ).startCluster();

        cluster.coreTx( ( db, tx ) ->
        {
            Node node = db.createNode( Label.label( "L" ) );
            for ( int i = 0; i < 10; i++ )
            {
                node.setProperty( "prop-" + i, "this is a quite long string to get to the log limit soonish" );
            }
            tx.success();
        } );

        long baseVersion = versionBy( cluster.awaitLeader().raftLogDirectory(), Math::max );

        CoreClusterMember coreGraphDatabase = null;
        for ( int j = 0; j < 2; j++ )
        {
            coreGraphDatabase = cluster.coreTx( ( db, tx ) ->
            {
                Node node = db.createNode( Label.label( "L" ) );
                for ( int i = 0; i < 10; i++ )
                {
                    node.setProperty( "prop-" + i, "this is a quite long string to get to the log limit soonish" );
                }
                tx.success();
            } );
        }

        File raftLogDir = coreGraphDatabase.raftLogDirectory();
        assertEventually( "pruning happened", () -> versionBy( raftLogDir, Math::min ), greaterThan( baseVersion ), 5,
                SECONDS );

        // when
        cluster.addReadReplicaWithIdAndRecordFormat( 42, HighLimit.NAME ).start();

        // then
        for ( final ReadReplica readReplica : cluster.readReplicas() )
        {
            assertEventually( "read replica available", () -> readReplica.database().isAvailable( 0 ), is( true ), 10,
                    SECONDS );
        }
    }

    private long versionBy( File raftLogDir, BinaryOperator<Long> operator )
    {
        SortedMap<Long,File> logs =
                new FileNames( raftLogDir ).getAllFiles( new DefaultFileSystemAbstraction(), mock( Log.class ) );
        return logs.keySet().stream().reduce( operator ).orElseThrow( IllegalStateException::new );
    }

    private final BiConsumer<CoreGraphDatabase,Transaction> createSomeData = ( db, tx ) ->
    {
        createData( db );
        tx.success();
    };

    private void createData( GraphDatabaseService db )
    {
        createData( db, 10 );
    }

    private void createData( GraphDatabaseService db, int amount )
    {
        for ( int i = 0; i < amount; i++ )
        {
            Node node = db.createNode(Label.label( "Foo" ));
            node.setProperty( "foobar", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar1", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar2", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar3", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar4", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar5", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar6", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar7", format( "baz_bat%s", UUID.randomUUID() ) );
            node.setProperty( "foobar8", format( "baz_bat%s", UUID.randomUUID() ) );
        }
    }
}
