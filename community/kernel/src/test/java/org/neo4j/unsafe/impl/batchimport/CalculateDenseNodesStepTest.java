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
package org.neo4j.unsafe.impl.batchimport;

import org.junit.Test;

import org.neo4j.kernel.impl.store.record.RelationshipRecord;
import org.neo4j.unsafe.impl.batchimport.cache.NodeRelationshipCache;
import org.neo4j.unsafe.impl.batchimport.input.Collector;
import org.neo4j.unsafe.impl.batchimport.input.InputRelationship;
import org.neo4j.unsafe.impl.batchimport.staging.StageControl;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.neo4j.unsafe.impl.batchimport.Configuration.DEFAULT;
import static org.neo4j.unsafe.impl.batchimport.input.InputEntity.NO_PROPERTIES;

public class CalculateDenseNodesStepTest
{
    @Test
    public void shouldNotProcessLoopsTwice() throws Exception
    {
        // GIVEN
        NodeRelationshipCache cache = mock( NodeRelationshipCache.class );
        try ( CalculateDenseNodesStep step = new CalculateDenseNodesStep( mock( StageControl.class ),
                DEFAULT, cache, mock( Collector.class ) ) )
        {
            step.processors( 4 );
            step.start( 0 );

            // WHEN
            Batch<InputRelationship,RelationshipRecord> batch = batch(
                    relationship( 1, 5 ),
                    relationship( 3, 10 ),
                    relationship( 2, 2 ), // <-- the loop
                    relationship( 4, 1 ) );
            step.receive( 0, batch );
            step.endOfUpstream();
            while ( !step.isCompleted() )
            {
                // wait
            }

            // THEN
            verify( cache, times( 2 ) ).incrementCount( eq( 1L ) );
            verify( cache, times( 1 ) ).incrementCount( eq( 2L ) );
            verify( cache, times( 1 ) ).incrementCount( eq( 3L ) );
            verify( cache, times( 1 ) ).incrementCount( eq( 4L ) );
            verify( cache, times( 1 ) ).incrementCount( eq( 5L ) );
            verify( cache, times( 1 ) ).incrementCount( eq( 10L ) );
        }
    }

    @Test
    public void shouldCollectBadRelationships() throws Exception
    {
        // GIVEN
        NodeRelationshipCache cache = mock( NodeRelationshipCache.class );
        Collector collector = mock( Collector.class );
        try ( CalculateDenseNodesStep step = new CalculateDenseNodesStep( mock( StageControl.class ),
                DEFAULT, cache, collector ) )
        {
            step.processors( 4 );
            step.start( 0 );

            // WHEN
            Batch<InputRelationship,RelationshipRecord> batch = batch(
                    relationship( 1, 5 ),
                    relationship( 3, 10 ),
                    relationship( "a", 2, -1, 2 ),     // <-- bad relationship with missing start node
                    relationship( 2, "b", 2, -1 ),     // <-- bad relationship with missing end node
                    relationship( "c", "d", -1, -1 ) );// <-- bad relationship with missing start and end node
            step.receive( 0, batch );
            step.endOfUpstream();
            while ( !step.isCompleted() )
            {
                //wait
            }

            // THEN
            verify( collector, times( 1 ) ).collectBadRelationship( any( InputRelationship.class ), eq( "a" ) );
            verify( collector, times( 1 ) ).collectBadRelationship( any( InputRelationship.class ), eq( "b" ) );
            verify( collector, times( 1 ) ).collectBadRelationship( any( InputRelationship.class ), eq( "c" ) );
            verify( collector, times( 1 ) ).collectBadRelationship( any( InputRelationship.class ), eq( "d" ) );
        }
    }

    private Batch<InputRelationship,RelationshipRecord> batch( Data... relationships )
    {
        Batch<InputRelationship,RelationshipRecord> batch = new Batch<>( new InputRelationship[relationships.length] );
        batch.ids = new long[relationships.length * 2];
        for ( int i = 0; i < relationships.length; i++ )
        {
            batch.input[i] = new InputRelationship( "test", i, i, NO_PROPERTIES, null, relationships[i].startNode,
                    relationships[i].endNode, "TYPE", null );
            batch.ids[i*2] = relationships[i].startNodeId;
            batch.ids[i*2 + 1] = relationships[i].endNodeId;
        }
        return batch;
    }

    private static Data relationship( Object startNode, Object endNode, long startNodeId, long endNodeId )
    {
        return new Data( startNode, endNode, startNodeId, endNodeId );
    }

    private static Data relationship( long startNodeId, long endNodeId )
    {
        return new Data( startNodeId, endNodeId, startNodeId, endNodeId );
    }

    private static class Data
    {
        private final long startNodeId;
        private final long endNodeId;
        private final Object startNode;
        private final Object endNode;

        Data( Object startNode, Object endNode, long startNodeId, long endNodeId )
        {
            this.startNode = startNode;
            this.endNode = endNode;
            this.startNodeId = startNodeId;
            this.endNodeId = endNodeId;
        }
    }
}
