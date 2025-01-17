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

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.index.IndexConfiguration;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.api.index.PropertyAccessor;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.index.IndexStoreView;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.factory.OperationalMode;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.DefaultFileSystemRule;

import static java.lang.Long.parseLong;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.neo4j.helpers.collection.Iterators.asSet;

public class LuceneSchemaIndexPopulatorTest
{
    @Rule
    public final DefaultFileSystemRule fs = new DefaultFileSystemRule();
    @Rule
    public TestDirectory testDir = TestDirectory.testDirectory();

    private IndexDescriptor indexDescriptor;
    private IndexStoreView indexStoreView;
    private LuceneSchemaIndexProvider provider;
    private Directory directory;
    private IndexPopulator indexPopulator;
    private IndexReader reader;
    private IndexSearcher searcher;
    private final long indexId = 0;
    private final int propertyKeyId = 666;

    @Before
    public void before() throws Exception
    {
        directory = new RAMDirectory();
        DirectoryFactory directoryFactory = new DirectoryFactory.Single(
                new DirectoryFactory.UncloseableDirectory( directory ) );
        provider = new LuceneSchemaIndexProvider( fs.get(), directoryFactory, testDir.directory( "folder" ),
                NullLogProvider.getInstance(), Config.empty(), OperationalMode.single );
        indexDescriptor = new IndexDescriptor( 42, propertyKeyId );
        indexStoreView = mock( IndexStoreView.class );
        IndexConfiguration indexConfig = IndexConfiguration.NON_UNIQUE;
        IndexSamplingConfig samplingConfig = new IndexSamplingConfig( Config.empty() );
        indexPopulator = provider.getPopulator( indexId, indexDescriptor, indexConfig, samplingConfig );
        indexPopulator.create();
        indexPopulator.configureSampling( true );
    }

    @After
    public void after() throws Exception
    {
        if ( reader != null )
        {
            reader.close();
        }
        directory.close();
    }

    @Test
    public void addingValuesShouldPersistThem() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "First" );
        addUpdate( indexPopulator, 2, "Second" );
        addUpdate( indexPopulator, 3, (byte) 1 );
        addUpdate( indexPopulator, 4, (short) 2 );
        addUpdate( indexPopulator, 5, 3 );
        addUpdate( indexPopulator, 6, 4L );
        addUpdate( indexPopulator, 7, 5F );
        addUpdate( indexPopulator, 8, 6D );

        // THEN
        assertIndexedValues(
                hit( "First", 1 ),
                hit( "Second", 2 ),
                hit( (byte)1, 3 ),
                hit( (short)2, 4 ),
                hit( 3, 5 ),
                hit( 4L, 6 ),
                hit( 5F, 7 ),
                hit( 6D, 8 ) );
    }

    @Test
    public void multipleEqualValues() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "value" );
        addUpdate( indexPopulator, 2, "value" );
        addUpdate( indexPopulator, 3, "value" );

        // THEN
        assertIndexedValues(
                hit( "value", 1L, 2L, 3L ) );
    }

    @Test
    public void multipleEqualValuesWithUpdateThatRemovesOne() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "value" );
        addUpdate( indexPopulator, 2, "value" );
        addUpdate( indexPopulator, 3, "value" );
        updatePopulator( indexPopulator, asList( remove( 2, "value" ) ), indexStoreView );

        // THEN
        assertIndexedValues(
                hit( "value", 1L, 3L ) );
    }

    @Test
    public void changeUpdatesInterleavedWithAdds() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "1" );
        addUpdate( indexPopulator, 2, "2" );
        updatePopulator( indexPopulator, asList( change( 1, "1", "1a" ) ), indexStoreView );
        addUpdate( indexPopulator, 3, "3" );

        // THEN
        assertIndexedValues(
                no( "1" ),
                hit( "1a", 1 ),
                hit( "2", 2 ),
                hit( "3", 3 ) );
    }

    @Test
    public void addUpdatesInterleavedWithAdds() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "1" );
        addUpdate( indexPopulator, 2, "2" );
        updatePopulator( indexPopulator, asList( remove( 1, "1" ), add( 1, "1a" ) ), indexStoreView );
        addUpdate( indexPopulator, 3, "3" );

        // THEN
        assertIndexedValues(
                hit( "1a", 1 ),
                hit( "2", 2 ),
                hit( "3", 3 ),
                no( "1" ) );
    }

    @Test
    public void removeUpdatesInterleavedWithAdds() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "1" );
        addUpdate( indexPopulator, 2, "2" );
        updatePopulator( indexPopulator, asList( remove( 2, "2" ) ), indexStoreView );
        addUpdate( indexPopulator, 3, "3" );

        // THEN
        assertIndexedValues(
                hit( "1", 1 ),
                no( "2" ),
                hit( "3", 3 ) );
    }

    @Test
    public void multipleInterleaves() throws Exception
    {
        // WHEN
        addUpdate( indexPopulator, 1, "1" );
        addUpdate( indexPopulator, 2, "2" );
        updatePopulator( indexPopulator, asList( change( 1, "1", "1a" ), change( 2, "2", "2a" ) ), indexStoreView );
        addUpdate( indexPopulator, 3, "3" );
        addUpdate( indexPopulator, 4, "4" );
        updatePopulator( indexPopulator, asList( change( 1, "1a", "1b" ), change( 4, "4", "4a" ) ), indexStoreView );

        // THEN
        assertIndexedValues(
                no( "1" ),
                no( "1a" ),
                hit( "1b", 1 ),
                no( "2" ),
                hit( "2a", 2 ),
                hit( "3", 3 ),
                no( "4" ),
                hit( "4a", 4 ) );
    }

    private Hit hit( Object value, Long... nodeIds )
    {
        return new Hit( value, nodeIds );
    }

    private Hit hit( Object value, long nodeId )
    {
        return new Hit( value, nodeId );
    }

    private Hit no( Object value )
    {
        return new Hit( value );
    }

    private static class Hit
    {
        private final Object value;
        private final Long[] nodeIds;

        Hit( Object value, Long... nodeIds )
        {
            this.value = value;
            this.nodeIds = nodeIds;
        }
    }

    private NodePropertyUpdate add( long nodeId, Object value )
    {
        return NodePropertyUpdate.add( nodeId, 0, value, new long[0] );
    }

    private NodePropertyUpdate change( long nodeId, Object valueBefore, Object valueAfter )
    {
        return NodePropertyUpdate.change( nodeId, 0, valueBefore, new long[0], valueAfter, new long[0] );
    }

    private NodePropertyUpdate remove( long nodeId, Object removedValue )
    {
        return NodePropertyUpdate.remove( nodeId, 0, removedValue, new long[0] );
    }

    private void assertIndexedValues( Hit... expectedHits ) throws IOException
    {
        switchToVerification();

        for ( Hit hit : expectedHits )
        {
            TopDocs hits = searcher.search( LuceneDocumentStructure.newSeekQuery( hit.value ), 10 );
            assertEquals( "Unexpected number of index results from " + hit.value, hit.nodeIds.length, hits.totalHits );
            Set<Long> foundNodeIds = new HashSet<>();
            for ( int i = 0; i < hits.totalHits; i++ )
            {
                Document document = searcher.doc( hits.scoreDocs[i].doc );
                foundNodeIds.add( parseLong( document.get( "id" ) ) );
            }
            assertEquals( asSet( hit.nodeIds ), foundNodeIds );
        }
    }

    private void switchToVerification() throws IOException
    {
        indexPopulator.close( true );
        assertEquals( InternalIndexState.ONLINE, provider.getInitialState( indexId ) );
        reader = DirectoryReader.open( directory );
        searcher = new IndexSearcher( reader );
    }

    private static void addUpdate( IndexPopulator populator, long nodeId, Object value )
            throws IOException, IndexEntryConflictException
    {
        populator.add( Collections.singletonList( NodePropertyUpdate.add( nodeId, 0, value, new long[]{0} ) ) );
    }

    private static void updatePopulator(
            IndexPopulator populator,
            Iterable<NodePropertyUpdate> updates,
            PropertyAccessor accessor )
            throws IOException, IndexEntryConflictException
    {
        try ( IndexUpdater updater = populator.newPopulatingUpdater( accessor ) )
        {
            for ( NodePropertyUpdate update :  updates )
            {
                updater.process( update );
            }
        }
    }
}
