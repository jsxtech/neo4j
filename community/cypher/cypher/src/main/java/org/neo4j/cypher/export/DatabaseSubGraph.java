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
package org.neo4j.cypher.export;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.IndexDefinition;

/**
 * @author mh
 * @since 18.02.13
 */
public class DatabaseSubGraph implements SubGraph
{
    private final GraphDatabaseService gdb;

    public DatabaseSubGraph( GraphDatabaseService gdb )
    {
        this.gdb = gdb;
    }

    public static SubGraph from( GraphDatabaseService gdb )
    {
        return new DatabaseSubGraph(gdb);
    }

    @Override
    public Iterable<Node> getNodes()
    {
        return gdb.getAllNodes();
    }

    @Override
    public Iterable<Relationship> getRelationships()
    {
        return gdb.getAllRelationships();
    }

    @Override
    public boolean contains(Relationship relationship)
    {
        return relationship.getGraphDatabase().equals(gdb);
    }

    @Override
    public Iterable<IndexDefinition> getIndexes()
    {
        return gdb.schema().getIndexes();
    }

    @Override
    public Iterable<ConstraintDefinition> getConstraints()
    {
        return gdb.schema().getConstraints();
    }
}
