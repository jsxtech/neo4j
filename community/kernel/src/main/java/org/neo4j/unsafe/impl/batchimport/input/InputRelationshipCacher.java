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
package org.neo4j.unsafe.impl.batchimport.input;

import java.io.IOException;

import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.impl.store.format.RecordFormats;

import static org.neo4j.unsafe.impl.batchimport.input.InputCache.HAS_TYPE_ID;
import static org.neo4j.unsafe.impl.batchimport.input.InputCache.NEW_TYPE;
import static org.neo4j.unsafe.impl.batchimport.input.InputCache.RELATIONSHIP_TYPE_TOKEN;
import static org.neo4j.unsafe.impl.batchimport.input.InputCache.SAME_TYPE;

/**
 * Caches {@link InputRelationship} to disk using a binary format.
 */
public class InputRelationshipCacher extends InputEntityCacher<InputRelationship>
{
    private String previousType;

    public InputRelationshipCacher( StoreChannel channel, StoreChannel header, RecordFormats recordFormats,
            int bufferSize, int batchSize )
            throws IOException
    {
        super( channel, header, recordFormats, bufferSize, batchSize, 2 );
    }

    @Override
    protected void writeEntity( InputRelationship relationship ) throws IOException
    {
        // properties
        super.writeEntity( relationship );

        // groups
        writeGroup( relationship.startNodeGroup(), 0 );
        writeGroup( relationship.endNodeGroup(), 1 );

        // ids
        writeValue( relationship.startNode() );
        writeValue( relationship.endNode() );

        // type
        if ( relationship.hasTypeId() )
        {
            channel.put( HAS_TYPE_ID );
            channel.putInt( relationship.typeId() );
        }
        else
        {
            if ( previousType != null && relationship.type().equals( previousType ) )
            {
                channel.put( SAME_TYPE );
            }
            else
            {
                channel.put( NEW_TYPE );
                writeToken( RELATIONSHIP_TYPE_TOKEN, previousType = relationship.type() );
            }
        }
    }

    @Override
    protected void clearState()
    {
        previousType = null;
        super.clearState();
    }
}
