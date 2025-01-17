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
package org.neo4j.causalclustering.catchup.storecopy;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import org.neo4j.causalclustering.identity.StoreId;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.AvailabilityGuard;
import org.neo4j.kernel.AvailabilityGuard.AvailabilityRequirement;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.impl.api.TransactionCommitProcess;
import org.neo4j.kernel.impl.api.TransactionRepresentationCommitProcess;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.StoreType;
import org.neo4j.kernel.impl.storemigration.StoreFile;
import org.neo4j.kernel.impl.transaction.log.TransactionAppender;
import org.neo4j.kernel.impl.transaction.state.DataSourceManager;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.storageengine.api.StorageEngine;

import static org.neo4j.kernel.AvailabilityGuard.availabilityRequirement;

public class LocalDatabase implements Lifecycle
{
    private static final AvailabilityRequirement NOT_STOPPED =
            availabilityRequirement( "Database is stopped" );
    private static final AvailabilityRequirement NOT_COPYING_STORE =
            availabilityRequirement( "Database is stopped to copy store from another cluster member" );

    private final File storeDir;

    private final StoreFiles storeFiles;
    private final DataSourceManager dataSourceManager;
    private final PageCache pageCache;
    private final FileSystemAbstraction fileSystemAbstraction;
    private final Supplier<DatabaseHealth> databaseHealthSupplier;
    private final AvailabilityGuard availabilityGuard;
    private final Log log;

    private volatile StoreId storeId;
    private volatile DatabaseHealth databaseHealth;
    private volatile AvailabilityRequirement currentRequirement;

    private volatile TransactionCommitProcess localCommit;

    public LocalDatabase( File storeDir, StoreFiles storeFiles,
            DataSourceManager dataSourceManager,
            PageCache pageCache, FileSystemAbstraction fileSystemAbstraction,
            Supplier<DatabaseHealth> databaseHealthSupplier, AvailabilityGuard availabilityGuard,
            LogProvider logProvider )
    {
        this.storeDir = storeDir;
        this.storeFiles = storeFiles;
        this.dataSourceManager = dataSourceManager;
        this.pageCache = pageCache;
        this.fileSystemAbstraction = fileSystemAbstraction;
        this.databaseHealthSupplier = databaseHealthSupplier;
        this.availabilityGuard = availabilityGuard;
        this.log = logProvider.getLog( getClass() );

        raiseAvailabilityGuard( NOT_STOPPED );
    }

    @Override
    public void init() throws Throwable
    {
        dataSourceManager.init();
    }

    @Override
    public synchronized void start() throws Throwable
    {
        storeId = readStoreIdFromDisk();
        log.info( "Starting with storeId: " + storeId );

        dataSourceManager.start();

        dropAvailabilityGuard();
    }

    @Override
    public void stop() throws Throwable
    {
        stopWithRequirement( NOT_STOPPED );
    }

    /**
     * Stop database to perform a store copy. This will raise {@link AvailabilityGuard} with
     * a more friendly blocking requirement.
     *
     * @throws Throwable if any of the components are unable to stop.
     */
    public void stopForStoreCopy() throws Throwable
    {
        stopWithRequirement( NOT_COPYING_STORE );
    }

    public boolean isAvailable()
    {
        return currentRequirement == null;
    }

    @Override
    public void shutdown() throws Throwable
    {
        dataSourceManager.shutdown();
    }

    public synchronized StoreId storeId()
    {
        if ( isAvailable() )
        {
            return storeId;
        }
        else
        {
            return readStoreIdFromDisk();
        }
    }

    private StoreId readStoreIdFromDisk()
    {
        try
        {
            File neoStoreFile = new File( storeDir, MetaDataStore.DEFAULT_NAME );
            org.neo4j.kernel.impl.store.StoreId kernelStoreId = MetaDataStore.getStoreId( pageCache, neoStoreFile );
            return new StoreId( kernelStoreId.getCreationTime(), kernelStoreId.getRandomId(),
                    kernelStoreId.getUpgradeTime(), kernelStoreId.getUpgradeId() );
        }
        catch ( IOException e )
        {
            log.error( "Failure reading store id", e );
            return null;
        }
    }

    public void panic( Throwable cause )
    {
        getDatabaseHealth().panic( cause );
    }

    public <EXCEPTION extends Throwable> void assertHealthy( Class<EXCEPTION> cause ) throws EXCEPTION
    {
        getDatabaseHealth().assertHealthy( cause );
    }

    private DatabaseHealth getDatabaseHealth()
    {
        if ( databaseHealth == null )
        {
            databaseHealth = databaseHealthSupplier.get();
        }
        return databaseHealth;
    }

    public void delete() throws IOException
    {
        storeFiles.delete( storeDir );
    }

    public boolean isEmpty() throws IOException
    {
        return !hasStoreFiles();
    }

    private boolean hasStoreFiles()
    {
        for ( StoreType storeType : StoreType.values() )
        {
            StoreFile storeFile = storeType.getStoreFile();
            if(storeFile != null)
            {
                boolean exists = fileSystemAbstraction.fileExists( new File( storeDir, storeFile.storeFileName() ) );
                if ( exists )
                {
                    return true;
                }
            }
        }
        return false;
    }

    public File storeDir()
    {
        return storeDir;
    }

    void replaceWith( File sourceDir ) throws IOException
    {
        storeFiles.delete( storeDir );
        storeFiles.moveTo( sourceDir, storeDir );
    }

    public NeoStoreDataSource dataSource()
    {
        return dataSourceManager.getDataSource();
    }

    /**
     * Called by the DataSourceManager during start.
     */
    public void registerCommitProcessDependencies( TransactionAppender appender, StorageEngine applier )
    {
        localCommit = new TransactionRepresentationCommitProcess( appender, applier );
    }

    public TransactionCommitProcess getCommitProcess()
    {
        return localCommit;
    }

    private synchronized void stopWithRequirement( AvailabilityRequirement requirement ) throws Throwable
    {
        log.info( "Stopping, reason: " + requirement.description() );
        raiseAvailabilityGuard( requirement );
        databaseHealth = null;
        localCommit = null;
        dataSourceManager.stop();
    }

    private void raiseAvailabilityGuard( AvailabilityRequirement requirement )
    {
        // it is possible for the local database to be created and stopped right after that to perform a store copy
        // in this case we need to impose new requirement and drop the old one
        availabilityGuard.require( requirement );
        if ( currentRequirement != null )
        {
            dropAvailabilityGuard();
        }
        currentRequirement = requirement;
    }

    private void dropAvailabilityGuard()
    {
        availabilityGuard.fulfill( currentRequirement );
        currentRequirement = null;
    }
}
