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
package org.neo4j.kernel.api.impl.schema;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.impl.index.LuceneAllDocumentsReader;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.test.rule.TestDirectory;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.neo4j.helpers.collection.Iterators.asList;
import static org.neo4j.helpers.collection.Iterators.emptySetOf;

public class LuceneSchemaIndexIT
{

    @Rule
    public TestDirectory testDir = TestDirectory.testDirectory();

    @Before
    public void before() throws Exception
    {
        System.setProperty( "luceneSchemaIndex.maxPartitionSize", "10" );
    }

    @After
    public void after() throws IOException
    {
        System.setProperty( "luceneSchemaIndex.maxPartitionSize", "" );
    }

    @Test
    public void snapshotForPartitionedIndex() throws Exception
    {
        // Given
        try ( LuceneIndexAccessor indexAccessor = createDefaultIndexAccessor() )
        {
            generateUpdates( indexAccessor, 32 );
            indexAccessor.force();

            // When & Then
            List<String> singlePartitionFileTemplates = Arrays.asList( ".cfe", ".cfs", ".si", "segments_1" );
            try ( ResourceIterator<File> snapshotIterator = indexAccessor.snapshotFiles() )
            {
                List<String> indexFileNames = asFileInsidePartitionNames( snapshotIterator );

                assertTrue( "Expect files from 4 partitions",
                        indexFileNames.size() >= (singlePartitionFileTemplates.size() * 4) );
                Map<String,Integer> templateMatches =
                        countTemplateMatches( singlePartitionFileTemplates, indexFileNames );

                for ( String fileTemplate : singlePartitionFileTemplates )
                {
                    Integer matches = templateMatches.get( fileTemplate );
                    assertTrue( "Expect to see at least 4 matches for template: " + fileTemplate, matches >= 4 );
                }
            }
        }
    }

    @Test
    public void snapshotForIndexWithNoCommits() throws Exception
    {
        // Given
        // A completely un-used index
        try ( LuceneIndexAccessor indexAccessor = createDefaultIndexAccessor() )
        {
            // When & Then
            try ( ResourceIterator<File> snapshotIterator = indexAccessor.snapshotFiles() )
            {
                assertThat( asUniqueSetOfNames( snapshotIterator ), equalTo( emptySetOf( String.class ) ) );
            }
        }
    }

    @Test
    public void updateMultiplePartitionedIndex() throws IOException, IndexEntryConflictException
    {
        try ( SchemaIndex index = LuceneSchemaIndexBuilder.create()
                .withIndexRootFolder( testDir.directory() )
                .withIndexIdentifier( "partitionedIndexForUpdates" )
                .build() )
        {
            index.create();
            index.open();
            addDocumentToIndex( index, 45 );

            index.getIndexWriter().updateDocument( LuceneDocumentStructure.newTermForChangeOrRemove( 100 ),
                    LuceneDocumentStructure.documentRepresentingProperty( 100, 100 ) );
            index.maybeRefreshBlocking();

            long documentsInIndex = Iterators.count( index.allDocumentsReader().iterator() );
            assertEquals( "Index should contain 45 added and 1 updated document.", 46, documentsInIndex );
        }
    }

    @Test
    public void createPopulateDropIndex() throws Exception
    {
        File crudOperation = testDir.directory( "indexCRUDOperation" );
        try ( SchemaIndex crudIndex = LuceneSchemaIndexBuilder.create()
                .withIndexRootFolder( crudOperation )
                .withIndexIdentifier( "crudIndex" )
                .build() )
        {
            crudIndex.open();

            addDocumentToIndex( crudIndex, 1 );
            assertEquals( 1, crudIndex.getPartitions().size() );

            addDocumentToIndex( crudIndex, 21 );
            assertEquals( 3, crudIndex.getPartitions().size() );

            crudIndex.drop();

            assertFalse( crudIndex.isOpen() );
            assertEquals( 0, crudOperation.list().length );
        }
    }

    @Test
    public void createFailPartitionedIndex() throws Exception
    {
        try ( SchemaIndex failedIndex = LuceneSchemaIndexBuilder.create()
                .withIndexRootFolder( testDir.directory( "failedIndexFolder" ) )
                .withIndexIdentifier( "failedIndex" )
                .build() )
        {
            failedIndex.open();

            addDocumentToIndex( failedIndex, 35 );
            assertEquals( 4, failedIndex.getPartitions().size() );

            failedIndex.markAsFailed( "Some failure" );
            failedIndex.flush();

            assertTrue( failedIndex.isOpen() );
            assertFalse( failedIndex.isOnline() );
        }
    }

    @Test
    public void openClosePartitionedIndex() throws IOException
    {
        SchemaIndex reopenIndex = null;
        try
        {
            reopenIndex = LuceneSchemaIndexBuilder.create()
                    .withIndexRootFolder( testDir.directory( "reopenIndexFolder" ) )
                    .withIndexIdentifier( "reopenIndex" )
                    .build();
            reopenIndex.open();

            addDocumentToIndex( reopenIndex, 1 );

            reopenIndex.close();
            assertFalse( reopenIndex.isOpen() );

            reopenIndex.open();
            assertTrue( reopenIndex.isOpen() );

            addDocumentToIndex( reopenIndex, 10 );

            reopenIndex.close();
            assertFalse( reopenIndex.isOpen() );

            reopenIndex.open();
            assertTrue( reopenIndex.isOpen() );

            reopenIndex.close();
            reopenIndex.open();
            addDocumentToIndex( reopenIndex, 100 );

            reopenIndex.maybeRefreshBlocking();

            try ( LuceneAllDocumentsReader allDocumentsReader = reopenIndex.allDocumentsReader() )
            {
                assertEquals( "All documents should be visible", 111, allDocumentsReader.maxCount() );
            }
        }
        finally
        {
            if ( reopenIndex != null )
            {
                reopenIndex.close();
            }
        }
    }

    private void addDocumentToIndex( SchemaIndex index, int documents ) throws IOException
    {
        for ( int i = 0; i < documents; i++ )
        {
            index.getIndexWriter().addDocument( LuceneDocumentStructure.documentRepresentingProperty( i, i ) );
        }
    }

    private LuceneIndexAccessor createDefaultIndexAccessor() throws IOException
    {
        SchemaIndex index = LuceneSchemaIndexBuilder.create()
                .withIndexRootFolder( testDir.directory() )
                .withIndexIdentifier( "testIndex" )
                .build();
        index.create();
        index.open();
        return new LuceneIndexAccessor( index, new IndexDescriptor( 1, 1 ) );
    }

    private Map<String,Integer> countTemplateMatches( List<String> nameTemplates, List<String> fileNames )
    {
        Map<String,Integer> templateMatches = new HashMap<>();
        for ( String indexFileName : fileNames )
        {
            for ( String template : nameTemplates )
            {
                if ( indexFileName.endsWith( template ) )
                {
                    templateMatches.put( template, templateMatches.getOrDefault( template, 0 ) + 1 );
                }
            }
        }
        return templateMatches;
    }

    private List<String> asFileInsidePartitionNames( ResourceIterator<File> resources )
    {
        int testDirectoryPathLength = testDir.directory().getAbsolutePath().length();
        return asList( resources ).stream()
                .map( file -> file.getAbsolutePath().substring( testDirectoryPathLength ) )
                .collect( Collectors.toList() );
    }

    private Set<String> asUniqueSetOfNames( ResourceIterator<File> files )
    {
        ArrayList<String> out = new ArrayList<>();
        while ( files.hasNext() )
        {
            String name = files.next().getName();
            out.add( name );
        }
        return Iterables.asUniqueSet( out );
    }

    private void generateUpdates( LuceneIndexAccessor indexAccessor, int nodesToUpdate )
            throws IOException, IndexEntryConflictException
    {
        try ( IndexUpdater updater = indexAccessor.newUpdater( IndexUpdateMode.ONLINE ) )
        {
            for ( int nodeId = 0; nodeId < nodesToUpdate; nodeId++ )
            {
                updater.process( add( nodeId, nodeId ) );
            }
        }
    }

    private NodePropertyUpdate add( long nodeId, Object value )
    {
        return NodePropertyUpdate.add( nodeId, 0, value, new long[0] );
    }

}
