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
package org.neo4j.kernel;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.NeoStoreDataSource.Diagnostics;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.core.DatabasePanicEventGenerator;
import org.neo4j.kernel.impl.logging.SimpleLogService;
import org.neo4j.kernel.impl.store.id.IdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.configuration.CommunityIdTypeConfigurationProvider;
import org.neo4j.kernel.impl.transaction.TransactionStats;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFiles;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryVersion;
import org.neo4j.kernel.impl.transaction.log.entry.LogHeader;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.LifecycleException;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.Logger;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.rule.NeoStoreDataSourceRule;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.logging.AssertableLogProvider.inLog;

public class NeoStoreDataSourceTest
{
    @Rule
    public EphemeralFileSystemRule fs = new EphemeralFileSystemRule();

    @Rule
    public TestDirectory dir = TestDirectory.testDirectory( fs.get() );

    @Rule
    public NeoStoreDataSourceRule dsRule = new NeoStoreDataSourceRule();

    @Rule
    public PageCacheRule pageCacheRule = new PageCacheRule();

    @Test
    public void databaseHealthShouldBeHealedOnStart() throws Throwable
    {
        NeoStoreDataSource theDataSource = null;
        try
        {
            DatabaseHealth databaseHealth = new DatabaseHealth( mock( DatabasePanicEventGenerator.class ),
                    NullLogProvider.getInstance().getLog( DatabaseHealth.class ) );

            theDataSource = dsRule.getDataSource( dir.graphDbDir(), fs.get(), pageCacheRule.getPageCache( fs.get() ),
                    stringMap(), databaseHealth );

            databaseHealth.panic( new Throwable() );

            theDataSource.start();

            databaseHealth.assertHealthy( Throwable.class );
        }
        finally
        {
            if ( theDataSource!= null )
            {
                theDataSource.stop();
                theDataSource.shutdown();
            }
        }
    }

    @Test
    public void flushOfThePageCacheHappensOnlyOnceDuringShutdown() throws IOException
    {
        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );
        NeoStoreDataSource ds = dsRule.getDataSource( dir.graphDbDir(), fs.get(), pageCache, stringMap() );

        ds.init();
        ds.start();
        verify( pageCache, never() ).flushAndForce();
        verify( pageCache, never() ).flushAndForce( any( IOLimiter.class ) );

        ds.stop();
        ds.shutdown();
        verify( pageCache ).flushAndForce( IOLimiter.unlimited() );
    }

    @Test
    public void flushOfThePageCacheOnShutdownHappensIfTheDbIsHealthy() throws IOException
    {
        DatabaseHealth health = mock( DatabaseHealth.class );
        when( health.isHealthy() ).thenReturn( true );

        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );

        NeoStoreDataSource ds = dsRule.getDataSource( dir.graphDbDir(), fs.get(), pageCache, stringMap(), health );

        ds.init();
        ds.start();
        verify( pageCache, never() ).flushAndForce();

        ds.stop();
        ds.shutdown();
        verify( pageCache ).flushAndForce( IOLimiter.unlimited() );
    }

    @Test
    public void flushOfThePageCacheOnShutdownDoesNotHappenIfTheDbIsUnhealthy() throws IOException
    {
        DatabaseHealth health = mock( DatabaseHealth.class );
        when( health.isHealthy() ).thenReturn( false );

        PageCache pageCache = spy( pageCacheRule.getPageCache( fs.get() ) );

        NeoStoreDataSource ds = dsRule.getDataSource( dir.graphDbDir(), fs.get(), pageCache, stringMap(), health );

        ds.init();
        ds.start();
        verify( pageCache, never() ).flushAndForce();

        ds.stop();
        ds.shutdown();
        verify( pageCache, never() ).flushAndForce( IOLimiter.unlimited() );
    }

    @Test
    public void shouldLogCorrectTransactionLogDiagnosticsForNoTransactionLogs() throws Exception
    {
        // GIVEN
        NeoStoreDataSource dataSource = neoStoreDataSourceWithLogFilesContainingLowestTxId( noLogs() );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        Logger logger = logProvider.getLog( getClass() ).infoLogger();

        // WHEN
        Diagnostics.TRANSACTION_RANGE.dump( dataSource, logger );

        // THEN
        logProvider.assertContainsMessageContaining( "No transactions" );
    }

    @Test
    public void shouldLogCorrectTransactionLogDiagnosticsForTransactionsInOldestLog() throws Exception
    {
        // GIVEN
        long logVersion = 2, prevLogLastTxId = 45;
        NeoStoreDataSource dataSource = neoStoreDataSourceWithLogFilesContainingLowestTxId(
                logWithTransactions( logVersion, prevLogLastTxId ) );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        Logger logger = logProvider.getLog( getClass() ).infoLogger();

        // WHEN
        Diagnostics.TRANSACTION_RANGE.dump( dataSource, logger );

        // THEN
        logProvider.assertContainsMessageContaining( "transaction " + (prevLogLastTxId + 1) );
        logProvider.assertContainsMessageContaining( "version " + logVersion );
    }

    @Test
    public void shouldLogCorrectTransactionLogDiagnosticsForTransactionsInSecondOldestLog() throws Exception
    {
        // GIVEN
        long logVersion = 2, prevLogLastTxId = 45;
        NeoStoreDataSource dataSource = neoStoreDataSourceWithLogFilesContainingLowestTxId(
                logWithTransactionsInNextToOldestLog( logVersion, prevLogLastTxId ) );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        Logger logger = logProvider.getLog( getClass() ).infoLogger();

        // WHEN
        Diagnostics.TRANSACTION_RANGE.dump( dataSource, logger );

        // THEN
        logProvider.assertContainsMessageContaining( "transaction " + (prevLogLastTxId + 1) );
        logProvider.assertContainsMessageContaining( "version " + (logVersion + 1) );
    }

    @Test
    public void logModuleSetUpError() throws Exception
    {
        Config config = new Config( stringMap(), GraphDatabaseSettings.class );
        IdGeneratorFactory idGeneratorFactory = mock( IdGeneratorFactory.class );
        Throwable openStoresError = new RuntimeException( "Can't set up modules" );
        doThrow( openStoresError ).when( idGeneratorFactory ).create( any( File.class ), anyLong(), anyBoolean() );

        CommunityIdTypeConfigurationProvider idTypeConfigurationProvider =
                new CommunityIdTypeConfigurationProvider();
        AssertableLogProvider logProvider = new AssertableLogProvider();
        SimpleLogService logService = new SimpleLogService( logProvider, logProvider );
        PageCache pageCache = pageCacheRule.getPageCache( fs.get() );

        NeoStoreDataSource dataSource = dsRule.getDataSource( dir.graphDbDir(), fs.get(), idGeneratorFactory,
                idTypeConfigurationProvider,
                pageCache, config.getParams(), mock( DatabaseHealth.class ), logService );

        try
        {
            dataSource.start();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertEquals( openStoresError, e );
        }

        logProvider.assertAtLeastOnce( inLog( NeoStoreDataSource.class ).warn(
                equalTo( "Exception occurred while setting up store modules. Attempting to close things down." ),
                equalTo( openStoresError ) ) );
    }

    @Test
    public void shouldAlwaysShutdownLifeEvenWhenCheckPointingFails() throws Exception
    {
        // Given
        File storeDir = dir.graphDbDir();
        FileSystemAbstraction fs = this.fs.get();
        PageCache pageCache = pageCacheRule.getPageCache( fs );
        DatabaseHealth databaseHealth = mock( DatabaseHealth.class );
        when( databaseHealth.isHealthy() ).thenReturn( true );
        IOException ex = new IOException( "boom!" );
        doThrow( ex ).when( databaseHealth )
                .assertHealthy( IOException.class ); // <- this is a trick to simulate a failure during checkpointing
        NeoStoreDataSource dataSource = dsRule.getDataSource( storeDir, fs, pageCache, emptyMap(), databaseHealth );
        dataSource.start();

        try
        {
            // When
            dataSource.stop();
            fail( "it should have thrown" );
        }
        catch ( LifecycleException e )
        {
            // Then
            assertEquals( ex, e.getCause() );
        }
    }

    @Test
    public void checkTransactionStatsWhenStopDataSource() throws IOException
    {
        NeoStoreDataSource dataSource =
                dsRule.getDataSource( dir.graphDbDir(), fs.get(), pageCacheRule.getPageCache( fs ), emptyMap() );
        TransactionStats transactionMonitor = dsRule.getTransactionMonitor();
        dataSource.start();

        Mockito.verifyZeroInteractions(transactionMonitor);

        dataSource.stop();
        verify( transactionMonitor, times( 2 ) ).getNumberOfTerminatedTransactions();
        verify( transactionMonitor, times( 2 ) ).getNumberOfRolledBackTransactions();
        verify( transactionMonitor, times( 2 ) ).getNumberOfStartedTransactions();
        verify( transactionMonitor, times( 2 ) ).getNumberOfCommittedTransactions();
    }

    private NeoStoreDataSource neoStoreDataSourceWithLogFilesContainingLowestTxId( PhysicalLogFiles files )
    {
        DependencyResolver resolver = mock( DependencyResolver.class );
        when( resolver.resolveDependency( PhysicalLogFiles.class ) ).thenReturn( files );
        NeoStoreDataSource dataSource = mock( NeoStoreDataSource.class );
        when( dataSource.getDependencyResolver() ).thenReturn( resolver );
        return dataSource;
    }

    private PhysicalLogFiles noLogs()
    {
        PhysicalLogFiles files = mock( PhysicalLogFiles.class );
        when( files.getLowestLogVersion() ).thenReturn( -1L );
        return files;
    }

    private PhysicalLogFiles logWithTransactions( long logVersion, long headerTxId ) throws IOException
    {
        PhysicalLogFiles files = mock( PhysicalLogFiles.class );
        when( files.getLowestLogVersion() ).thenReturn( logVersion );
        when( files.hasAnyEntries( logVersion ) ).thenReturn( true );
        when( files.versionExists( logVersion ) ).thenReturn( true );
        when( files.extractHeader( logVersion ) ).thenReturn( new LogHeader( LogEntryVersion.CURRENT.byteCode(),
                logVersion, headerTxId ) );
        return files;
    }

    private PhysicalLogFiles logWithTransactionsInNextToOldestLog( long logVersion, long prevLogLastTxId )
            throws IOException
    {
        PhysicalLogFiles files = logWithTransactions( logVersion + 1, prevLogLastTxId );
        when( files.getLowestLogVersion() ).thenReturn( logVersion );
        when( files.hasAnyEntries( logVersion ) ).thenReturn( false );
        when( files.versionExists( logVersion ) ).thenReturn( true );
        return files;
    }
}
