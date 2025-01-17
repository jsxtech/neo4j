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
package org.neo4j.kernel.impl.query;

import org.neo4j.helpers.Service;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

public abstract class QueryEngineProvider extends Service
{
    public QueryEngineProvider( String name )
    {
        super( name );
    }

    protected abstract QueryExecutionEngine createEngine( Dependencies deps, GraphDatabaseAPI graphAPI );

    public static QueryExecutionEngine initialize( Dependencies deps, GraphDatabaseAPI graphAPI,
            Iterable<QueryEngineProvider> providers )
    {
        QueryEngineProvider provider = null;
        for ( QueryEngineProvider candidate : providers )
        {
            if ( provider == null )
            {
                provider = candidate;
            }
            else
            {
                throw new IllegalStateException( "Too many query engines." );
            }
        }
        if ( provider == null )
        {
            return noEngine();
        }
        QueryExecutionEngine engine = provider.createEngine( deps, graphAPI );
        return deps.satisfyDependency( engine );
    }

    public static QueryExecutionEngine noEngine()
    {
        return NoQueryEngine.INSTANCE;
    }

    public static QuerySource describe()
    {
        return new QuerySource( "embedded-session" );
    }
}
