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
package org.neo4j.kernel.impl.store.format.highlimit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.File;
import java.io.IOException;

import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.logging.NullLogService;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.format.CapabilityType;
import org.neo4j.kernel.impl.store.format.highlimit.v300.HighLimitV3_0_0;
import org.neo4j.kernel.impl.storemigration.monitoring.MigrationProgressMonitor;
import org.neo4j.kernel.impl.storemigration.participant.StoreMigrator;
import org.neo4j.test.rule.PageCacheRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.neo4j.kernel.impl.store.MetaDataStore.Position.STORE_VERSION;

public class HighLimitStoreMigrationTest
{
    private final PageCacheRule pageCacheRule = new PageCacheRule();
    private final TestDirectory testDirectory = TestDirectory.testDirectory();
    private final DefaultFileSystemRule fileSystemRule = new DefaultFileSystemRule();
    @Rule
    public RuleChain chain = RuleChain.outerRule( pageCacheRule )
                                      .around( fileSystemRule )
                                      .around( testDirectory );

    @Test
    public void haveDifferentFormatCapabilitiesAsHighLimit3_0()
    {
        assertFalse( HighLimit.RECORD_FORMATS.hasSameCapabilities( HighLimitV3_0_0.RECORD_FORMATS, CapabilityType.FORMAT ) );
    }

    @Test
    public void migrateHighLimit3_0StoreFiles() throws IOException
    {
        DefaultFileSystemAbstraction fileSystem = fileSystemRule.get();
        PageCache pageCache = pageCacheRule.getPageCache( fileSystem );
        SchemaIndexProvider schemaIndexProvider = mock( SchemaIndexProvider.class );

        StoreMigrator migrator = new StoreMigrator( fileSystem, pageCache, Config.empty(), NullLogService.getInstance(),
                                                    schemaIndexProvider );

        File storeDir = new File( testDirectory.graphDbDir(), "storeDir" );
        File migrationDir = new File( testDirectory.graphDbDir(), "migrationDir" );
        fileSystem.mkdir( migrationDir );
        fileSystem.mkdir( storeDir );

        prepareNeoStoreFile( fileSystem, storeDir, HighLimitV3_0_0.STORE_VERSION, pageCache );

        MigrationProgressMonitor.Section progressMonitor = mock( MigrationProgressMonitor.Section.class );

        migrator.migrate( storeDir, migrationDir, progressMonitor, HighLimitV3_0_0.STORE_VERSION, HighLimit.STORE_VERSION );

        int newStoreFilesCount = fileSystem.listFiles( migrationDir ).length;
        assertEquals( "Store should be migrated and new store files should be created.", 17, newStoreFilesCount );
    }

    private File prepareNeoStoreFile( FileSystemAbstraction fileSystem, File storeDir, String storeVersion,
            PageCache pageCache ) throws IOException
    {
        File neoStoreFile = createNeoStoreFile( fileSystem, storeDir );
        long value = MetaDataStore.versionStringToLong( storeVersion );
        MetaDataStore.setRecord( pageCache, neoStoreFile, STORE_VERSION, value );
        return neoStoreFile;
    }

    private File createNeoStoreFile( FileSystemAbstraction fileSystem, File storeDir ) throws IOException
    {
        fileSystem.mkdir( storeDir );
        File neoStoreFile = new File( storeDir, MetaDataStore.DEFAULT_NAME );
        fileSystem.create( neoStoreFile ).close();
        return neoStoreFile;
    }

}
