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
package org.neo4j.kernel.impl.storemigration.participant;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.impl.store.format.CapabilityType;
import org.neo4j.kernel.impl.store.format.RecordFormatSelector;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.storemigration.monitoring.MigrationProgressMonitor;
import org.neo4j.kernel.spi.legacyindex.IndexImplementation;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.upgrade.lucene.LegacyIndexMigrationException;
import org.neo4j.upgrade.lucene.LuceneLegacyIndexUpgrader;
import org.neo4j.upgrade.lucene.LuceneLegacyIndexUpgrader.Monitor;

/**
 * Migrates legacy lucene indexes between different neo4j versions.
 * Participates in store upgrade as one of the migration participants.
 */
public class LegacyIndexMigrator extends AbstractStoreMigrationParticipant
{
    private static final String LUCENE_LEGACY_INDEX_PROVIDER_NAME = "lucene";
    private final Map<String,IndexImplementation> indexProviders;
    private final FileSystemAbstraction fileSystem;
    private File migrationLegacyIndexesRoot;
    private File originalLegacyIndexesRoot;
    private final Log log;
    private boolean legacyIndexMigrated = false;

    public LegacyIndexMigrator( FileSystemAbstraction fileSystem, Map<String,IndexImplementation> indexProviders,
            LogProvider logProvider )
    {
        super( "Legacy indexes" );
        this.fileSystem = fileSystem;
        this.indexProviders = indexProviders;
        this.log = logProvider.getLog( getClass() );
    }

    @Override
    public void migrate( File storeDir, File migrationDir, MigrationProgressMonitor.Section progressMonitor,
            String versionToMigrateFrom, String versionToMigrateTo ) throws IOException
    {
        IndexImplementation indexImplementation = indexProviders.get( LUCENE_LEGACY_INDEX_PROVIDER_NAME );
        if ( indexImplementation != null )
        {
            RecordFormats from = RecordFormatSelector.selectForVersion( versionToMigrateFrom );
            RecordFormats to = RecordFormatSelector.selectForVersion( versionToMigrateTo );
            if ( !from.hasSameCapabilities( to, CapabilityType.INDEX ) )
            {
                originalLegacyIndexesRoot = indexImplementation.getIndexImplementationDirectory( storeDir );
                migrationLegacyIndexesRoot = indexImplementation.getIndexImplementationDirectory( migrationDir );
                if ( isNotEmptyDirectory( originalLegacyIndexesRoot ) )
                {
                    migrateLegacyIndexes( progressMonitor );
                    legacyIndexMigrated = true;
                }
            }
        }
        else
        {
            log.debug( "Lucene index provider not found, nothing to migrate." );
        }
    }

    @Override
    public void moveMigratedFiles( File migrationDir, File storeDir, String versionToMigrateFrom,
            String versionToMigrateTo )
            throws IOException
    {
        if ( legacyIndexMigrated )
        {
            fileSystem.deleteRecursively( originalLegacyIndexesRoot );
            fileSystem.moveToDirectory( migrationLegacyIndexesRoot, originalLegacyIndexesRoot.getParentFile() );
        }
    }

    @Override
    public void cleanup( File migrationDir ) throws IOException
    {
        if ( isIndexMigrationDirectoryExists() )
        {
            fileSystem.deleteRecursively( migrationLegacyIndexesRoot );
        }
    }

    private boolean isIndexMigrationDirectoryExists()
    {
        return migrationLegacyIndexesRoot != null && fileSystem.fileExists( migrationLegacyIndexesRoot );
    }

    private void migrateLegacyIndexes( MigrationProgressMonitor.Section progressMonitor ) throws IOException
    {
        try
        {
            fileSystem.copyRecursively( originalLegacyIndexesRoot, migrationLegacyIndexesRoot );
            Path indexRootPath = migrationLegacyIndexesRoot.toPath();
            LuceneLegacyIndexUpgrader indexUpgrader = createLuceneLegacyIndexUpgrader( indexRootPath, progressMonitor );
            indexUpgrader.upgradeIndexes();
        }
        catch ( LegacyIndexMigrationException lime )
        {
            log.error( "Migration of legacy indexes failed. Index: " + lime.getFailedIndexName() + " can't be " +
                       "migrated.", lime );
            throw new IOException( "Legacy index migration failed.", lime );
        }
    }

    private boolean isNotEmptyDirectory(File file)
    {
        if (fileSystem.isDirectory( file ))
        {
            File[] files = fileSystem.listFiles( file );
            return files != null && files.length > 0;
        }
        return false;
    }

    LuceneLegacyIndexUpgrader createLuceneLegacyIndexUpgrader( Path indexRootPath,
            MigrationProgressMonitor.Section progressMonitor )
    {
        return new LuceneLegacyIndexUpgrader( indexRootPath, progressMonitor( progressMonitor ) );
    }

    private Monitor progressMonitor( MigrationProgressMonitor.Section progressMonitor )
    {
        return new Monitor()
        {
            @Override
            public void starting( int total )
            {
                progressMonitor.start( total );
            }

            @Override
            public void migrated( String name )
            {
                progressMonitor.progress( 1 );
            }
        };
    }
}
