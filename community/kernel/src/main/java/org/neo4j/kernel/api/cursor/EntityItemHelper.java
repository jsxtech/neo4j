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
package org.neo4j.kernel.api.cursor;

import org.neo4j.collection.primitive.PrimitiveIntCollection;
import org.neo4j.collection.primitive.PrimitiveIntStack;
import org.neo4j.cursor.Cursor;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.storageengine.api.EntityItem;
import org.neo4j.storageengine.api.PropertyItem;

public abstract class EntityItemHelper
        implements EntityItem
{
    @Override
    public boolean hasProperty( int propertyKeyId )
    {
        try ( Cursor<PropertyItem> cursor = property( propertyKeyId ) )
        {
            return cursor.next();
        }
    }

    @Override
    public Object getProperty( int propertyKeyId )
    {
        try ( Cursor<PropertyItem> cursor = property( propertyKeyId ) )
        {
            if ( cursor.next() )
            {
                return cursor.get().value();
            }
        }
        catch ( NotFoundException e )
        {
            return null;
        }

        return null;
    }

    @Override
    public PrimitiveIntCollection getPropertyKeys()
    {
        PrimitiveIntStack keys = new PrimitiveIntStack();
        try ( Cursor<PropertyItem> properties = properties() )
        {
            while ( properties.next() )
            {
                keys.push( properties.get().propertyKeyId() );
            }
        }

        return keys;
    }
}
