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
package org.neo4j.kernel.impl.store.record;

import org.neo4j.kernel.api.constraints.RelationshipPropertyConstraint;

public abstract class RelationshipPropertyConstraintRule extends PropertyConstraintRule
{
    protected final int relationshipType;

    public RelationshipPropertyConstraintRule( long id, int relationshipType, Kind kind )
    {
        super( id, kind );
        this.relationshipType = relationshipType;
    }

    @Override
    public final int getLabel()
    {
        throw new IllegalStateException( "Constraint rule is associated with relationships" );
    }

    @Override
    public final int getRelationshipType()
    {
        return relationshipType;
    }

    @Override
    public abstract RelationshipPropertyConstraint toConstraint();

    @Override
    public boolean equals( Object o )
    {
        if ( this == o )
        {
            return true;
        }
        if ( o == null || getClass() != o.getClass() )
        {
            return false;
        }
        if ( !super.equals( o ) )
        {
            return false;
        }
        return relationshipType == ((RelationshipPropertyConstraintRule) o).relationshipType;
    }

    @Override
    public int hashCode()
    {
        return 31 * super.hashCode() + relationshipType;
    }
}
