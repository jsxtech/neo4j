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
package org.neo4j.graphdb;

import org.junit.Test;

import org.neo4j.graphdb.index.IndexManager;

import static org.neo4j.graphdb.IndexManagerFacadeMethods.ALL_INDEX_MANAGER_FACADE_METHODS;

public class MandatoryTransactionsForIndexManagerFacadeTests extends AbstractMandatoryTransactionsTest<IndexManager>
{
    @Test
    public void shouldRequireTransactionsWhenCallingMethodsOnIndexManagerFacade() throws Exception
    {
        assertFacadeMethodsThrowNotInTransaction( obtainEntity(), ALL_INDEX_MANAGER_FACADE_METHODS );
    }

    @Test
    public void shouldTerminateWhenCallingMethodsOnIndexManagerFacade() throws Exception
    {
        assertFacadeMethodsThrowAfterTerminate( ALL_INDEX_MANAGER_FACADE_METHODS );
    }

    @Override
    protected IndexManager obtainEntityInTransaction( GraphDatabaseService graphDatabaseService )
    {
        return graphDatabaseService.index();
    }
}
