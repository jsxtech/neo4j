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
package org.neo4j.commandline.dbms;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.neo4j.commandline.admin.CommandLocator;
import org.neo4j.commandline.admin.IncorrectUsage;
import org.neo4j.commandline.admin.NullOutsideWorld;
import org.neo4j.commandline.admin.OutsideWorld;
import org.neo4j.commandline.admin.RealOutsideWorld;
import org.neo4j.commandline.admin.Usage;
import org.neo4j.helpers.Args;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.storemigration.StoreFileType;
import org.neo4j.test.rule.TestDirectory;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ImportCommandTest
{
    @Rule
    public final TestDirectory testDir = TestDirectory.testDirectory();

    @Test
    public void defaultsToCsvWhenModeNotSpecified() throws Exception
    {
        File homeDir = testDir.directory( "home" );
        ImporterFactory mockImporterFactory = mock( ImporterFactory.class );
        when( mockImporterFactory
                .getImporterForMode( eq( "csv" ), any( Args.class ), any( Config.class ), any( OutsideWorld.class ) ) )
                .thenReturn( mock( Importer.class ) );

        ImportCommand importCommand =
                new ImportCommand( homeDir.toPath(), testDir.directory( "conf" ).toPath(), new RealOutsideWorld(),
                        mockImporterFactory );

        String[] arguments = {"--database=foo", "--from=bar"};

        importCommand.execute( arguments );

        verify( mockImporterFactory )
                .getImporterForMode( eq( "csv" ), any( Args.class ), any( Config.class ), any( OutsideWorld.class ) );
    }

    @Test
    public void acceptsNodeMetadata() throws Exception
    {
        File homeDir = testDir.directory( "home" );
        ImporterFactory mockImporterFactory = mock( ImporterFactory.class );
        when( mockImporterFactory
                .getImporterForMode( eq( "csv" ), any( Args.class ), any( Config.class ), any( OutsideWorld.class ) ) )
                .thenReturn( mock( Importer.class ) );

        ImportCommand importCommand =
                new ImportCommand( homeDir.toPath(), testDir.directory( "conf" ).toPath(), new RealOutsideWorld(),
                        mockImporterFactory );

        String[] arguments = {"--database=foo", "--from=bar", "--nodes:PERSON:FRIEND=mock.csv"};

        importCommand.execute( arguments );

        verify( mockImporterFactory )
                .getImporterForMode( eq( "csv" ), any( Args.class ), any( Config.class ), any( OutsideWorld.class ) );
    }

    @Test
    public void acceptsRelationshipsMetadata() throws Exception
    {
        File homeDir = testDir.directory( "home" );
        ImporterFactory mockImporterFactory = mock( ImporterFactory.class );
        when( mockImporterFactory
                .getImporterForMode( eq( "csv" ), any( Args.class ), any( Config.class ), any( OutsideWorld.class ) ) )
                .thenReturn( mock( Importer.class ) );

        ImportCommand importCommand =
                new ImportCommand( homeDir.toPath(), testDir.directory( "conf" ).toPath(), new RealOutsideWorld(),
                        mockImporterFactory );

        String[] arguments = {"--database=foo", "--from=bar", "--relationships:LIKES:HATES=mock.csv"};

        importCommand.execute( arguments );

        verify( mockImporterFactory )
                .getImporterForMode( eq( "csv" ), any( Args.class ), any( Config.class ), any( OutsideWorld.class ) );
    }

    @Test
    public void requiresDatabaseArgument() throws Exception
    {
        ImportCommand importCommand =
                new ImportCommand( testDir.directory( "home" ).toPath(), testDir.directory( "conf" ).toPath(),
                        new NullOutsideWorld() );

        String[] arguments = {"--mode=database", "--from=bar"};
        try
        {
            importCommand.execute( arguments );
            fail( "Should have thrown an exception." );
        }
        catch ( IncorrectUsage e )
        {
            assertThat( e.getMessage(), containsString( "database" ) );
        }
    }

    @Test
    public void failIfInvalidModeSpecified() throws Exception
    {
        ImportCommand importCommand =
                new ImportCommand( testDir.directory( "home" ).toPath(), testDir.directory( "conf" ).toPath(),
                        new NullOutsideWorld() );

        String[] arguments = {"--mode=foo", "--database=bar", "--from=baz"};
        try
        {
            importCommand.execute( arguments );
            fail( "Should have thrown an exception." );
        }
        catch ( IncorrectUsage e )
        {
            assertThat( e.getMessage(), containsString( "foo" ) );
        }
    }

    @Test
    public void failIfDestinationDatabaseAlreadyExists() throws Exception
    {
        Path homeDir = testDir.directory( "home" ).toPath();
        ImportCommand importCommand =
                new ImportCommand( homeDir, testDir.directory( "conf" ).toPath(), new NullOutsideWorld() );

        putStoreInDirectory( homeDir.resolve( "data" ).resolve( "databases" ).resolve( "existing.db" ) );
        String[] arguments = {"--mode=csv", "--database=existing.db"};
        try
        {
            importCommand.execute( arguments );
            fail( "Should have thrown an exception." );
        }
        catch ( Exception e )
        {
            assertThat( e.getMessage(), containsString( "already contains a database" ) );
        }
    }

    @Test
    public void shouldPrintNiceHelp() throws Throwable
    {
        try ( ByteArrayOutputStream baos = new ByteArrayOutputStream() )
        {
            PrintStream ps = new PrintStream( baos );

            Usage usage = new Usage( "neo4j-admin", mock( CommandLocator.class ) );
            usage.printUsageForCommand( new ImportCommandProvider(), ps::println );

            assertEquals( String.format( "usage: neo4j-admin import [--mode=csv] [--database=<name>]%n" +
                            "                          [--additional-config=<config-file-path>]%n" +
                            "                          [--report-file=<filename>]%n" +
                            "                          [--nodes[:Label1:Label2]=<\"file1,file2,...\">]%n" +
                            "                          [--relationships[:RELATIONSHIP_TYPE]=<\"file1,file2,...\">]%n" +
                            "                          [--id-type=<STRING|INTEGER|ACTUAL>]%n" +
                            "                          [--input-encoding=<character-set>]%n" +
                            "usage: neo4j-admin import --mode=database [--database=<name>]%n" +
                            "                          [--additional-config=<config-file-path>]%n" +
                            "                          [--from=<source-directory>]%n" +
                            "%n" +
                            "Import a collection of CSV files with --mode=csv (default), or a database from a%n" +
                            "pre-3.0 installation with --mode=database.%n" +
                            "%n" +
                            "options:%n" +
                            "  --database=<name>%n" +
                            "      Name of database. [default:graph.db]%n" +
                            "  --additional-config=<config-file-path>%n" +
                            "      Configuration file to supply additional configuration in. [default:]%n" +
                            "  --mode=<database|csv>%n" +
                            "      Import a collection of CSV files or a pre-3.0 installation. [default:csv]%n" +
                            "  --from=<source-directory>%n" +
                            "      The location of the pre-3.0 database (e.g. <neo4j-root>/data/graph.db).%n" +
                            "      [default:]%n" +
                            "  --report-file=<filename>%n" +
                            "      File in which to store the report of the csv-import.%n" +
                            "      [default:import.report]%n" +
                            "  --nodes[:Label1:Label2]=<\"file1,file2,...\">%n" +
                            "      Node CSV header and data. Multiple files will be logically seen as one big%n" +
                            "      file from the perspective of the importer. The first line must contain the%n" +
                            "      header. Multiple data sources like these can be specified in one import,%n" +
                            "      where each data source has its own header. Note that file groups must be%n" +
                            "      enclosed in quotation marks. [default:]%n" +
                            "  --relationships[:RELATIONSHIP_TYPE]=<\"file1,file2,...\">%n" +
                            "      Relationship CSV header and data. Multiple files will be logically seen as%n" +
                            "      one big file from the perspective of the importer. The first line must%n" +
                            "      contain the header. Multiple data sources like these can be specified in%n" +
                            "      one import, where each data source has its own header. Note that file%n" +
                            "      groups must be enclosed in quotation marks. [default:]%n" +
                            "  --id-type=<STRING|INTEGER|ACTUAL>%n" +
                            "      Each node must provide a unique id. This is used to find the correct nodes%n" +
                            "      when creating relationships. Possible values are:%n" +
                            "        STRING: arbitrary strings for identifying nodes,%n" +
                            "        INTEGER: arbitrary integer values for identifying nodes,%n" +
                            "        ACTUAL: (advanced) actual node ids.%n" +
                            "      For more information on id handling, please see the Neo4j Manual:%n" +
                            "      https://neo4j.com/docs/operations-manual/current/tools/import/%n" +
                            "      [default:STRING]%n" +
                            "  --input-encoding=<character-set>%n" +
                            "      Character set that input data is encoded in. [default:UTF-8]%n" ),
                    baos.toString() );
        }
    }

    private void putStoreInDirectory( Path storeDir ) throws IOException
    {
        Files.createDirectories( storeDir );
        Path storeFile = storeDir.resolve( StoreFileType.STORE.augment( MetaDataStore.DEFAULT_NAME ) );
        Files.createFile( storeFile );
    }
}
