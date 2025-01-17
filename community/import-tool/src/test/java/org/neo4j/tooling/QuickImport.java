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
package org.neo4j.tooling;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;

import org.neo4j.csv.reader.CharSeeker;
import org.neo4j.csv.reader.CharSeekers;
import org.neo4j.csv.reader.Extractors;
import org.neo4j.csv.reader.Readables;
import org.neo4j.helpers.Args;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.logging.SimpleLogService;
import org.neo4j.logging.FormattedLogProvider;
import org.neo4j.unsafe.impl.batchimport.BatchImporter;
import org.neo4j.unsafe.impl.batchimport.ParallelBatchImporter;
import org.neo4j.unsafe.impl.batchimport.input.Input;
import org.neo4j.unsafe.impl.batchimport.input.csv.Configuration;
import org.neo4j.unsafe.impl.batchimport.input.csv.Header;
import org.neo4j.unsafe.impl.batchimport.input.csv.IdType;

import static java.lang.System.currentTimeMillis;

import static org.neo4j.graphdb.factory.GraphDatabaseSettings.dense_node_threshold;
import static org.neo4j.kernel.configuration.Settings.parseLongWithUnit;
import static org.neo4j.tooling.DataGeneratorInput.bareboneNodeHeader;
import static org.neo4j.tooling.DataGeneratorInput.bareboneRelationshipHeader;
import static org.neo4j.unsafe.impl.batchimport.input.Collectors.silentBadCollector;
import static org.neo4j.unsafe.impl.batchimport.input.csv.Configuration.COMMAS;
import static org.neo4j.unsafe.impl.batchimport.input.csv.DataFactories.defaultFormatNodeFileHeader;
import static org.neo4j.unsafe.impl.batchimport.input.csv.DataFactories.defaultFormatRelationshipFileHeader;
import static org.neo4j.unsafe.impl.batchimport.staging.ExecutionMonitors.defaultVisible;

/**
 * Uses all available shortcuts to as quickly as possible import as much data as possible. Usage of this
 * utility is most likely just testing behavior of some components in the face of various dataset sizes,
 * even quite big ones. Uses the import tool, or rather directly the {@link ParallelBatchImporter}.
 *
 * Quick comes from gaming terminology where you sometimes just want to play a quick game, without
 * any settings or hazzle, just play.
 *
 * Uses {@link DataGeneratorInput} as random data {@link Input}.
 *
 * For the time being the node/relationship data can't be controlled via command-line arguments,
 * only through changing the code. The {@link DataGeneratorInput} accepts two {@link Header headers}
 * describing which sort of data it should generate.
 */
public class QuickImport
{
    public static void main( String[] arguments ) throws IOException
    {
        Args args = Args.parse( arguments );
        long nodeCount = parseLongWithUnit( args.get( "nodes", null ) );
        long relationshipCount = parseLongWithUnit( args.get( "relationships", null ) );
        int labelCount = args.getNumber( "labels", 4 ).intValue();
        int relationshipTypeCount = args.getNumber( "relationship-types", 4 ).intValue();
        File dir = new File( args.get( ImportTool.Options.STORE_DIR.key() ) );
        long randomSeed = args.getNumber( "random-seed", currentTimeMillis() ).longValue();
        Configuration config = COMMAS;

        Extractors extractors = new Extractors( config.arrayDelimiter() );
        IdType idType = IdType.valueOf( args.get( "id-type", IdType.ACTUAL.name() ) );

        Header nodeHeader = parseNodeHeader( args, idType, extractors );
        Header relationshipHeader = parseRelationshipHeader( args, idType, extractors );

        FormattedLogProvider sysoutLogProvider = FormattedLogProvider.toOutputStream( System.out );
        org.neo4j.unsafe.impl.batchimport.Configuration importConfig =
                new org.neo4j.unsafe.impl.batchimport.Configuration()
        {
            @Override
            public int maxNumberOfProcessors()
            {
                return args.getNumber( ImportTool.Options.PROCESSORS.key(), DEFAULT.maxNumberOfProcessors() ).intValue();
            }

            @Override
            public int denseNodeThreshold()
            {
                return args.getNumber( dense_node_threshold.name(), DEFAULT.denseNodeThreshold() ).intValue();
            }
        };

        SimpleDataGenerator generator = new SimpleDataGenerator( nodeHeader, relationshipHeader, randomSeed,
                nodeCount, labelCount, relationshipTypeCount, idType );
        Input input = new DataGeneratorInput(
                nodeCount, relationshipCount,
                generator.nodes(), generator.relationships(),
                idType, silentBadCollector( 0 ) );

        BatchImporter consumer;
        if ( args.getBoolean( "to-csv" ) )
        {
            consumer = new CsvOutput( dir, nodeHeader, relationshipHeader, config );
        }
        else
        {
            consumer = new ParallelBatchImporter( dir, importConfig,
                    new SimpleLogService( sysoutLogProvider, sysoutLogProvider ),
                    defaultVisible(),
                    Config.defaults() );
        }
        consumer.doImport( input );
    }

    private static Header parseNodeHeader( Args args, IdType idType, Extractors extractors )
    {
        String definition = args.get( "node-header", null );
        if ( definition == null )
        {
            return bareboneNodeHeader( idType, extractors );
        }

        Configuration config = Configuration.COMMAS;
        return defaultFormatNodeFileHeader().create( seeker( definition, config ), config, idType );
    }

    private static Header parseRelationshipHeader( Args args, IdType idType, Extractors extractors )
    {
        String definition = args.get( "relationship-header", null );
        if ( definition == null )
        {
            return bareboneRelationshipHeader( idType, extractors );
        }

        Configuration config = Configuration.COMMAS;
        return defaultFormatRelationshipFileHeader().create( seeker( definition, config ), config, idType );
    }

    private static CharSeeker seeker( String definition, Configuration config )
    {
        return CharSeekers.charSeeker( Readables.wrap( new StringReader( definition ) ),
                new org.neo4j.csv.reader.Configuration.Overridden( config )
        {
            @Override
            public int bufferSize()
            {
                return 10_000;
            }
        }, false );
    }
}
