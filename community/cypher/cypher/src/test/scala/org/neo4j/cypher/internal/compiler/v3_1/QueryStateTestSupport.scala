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
package org.neo4j.cypher.internal.compiler.v3_1

import org.neo4j.cypher.GraphDatabaseTestSupport
import org.neo4j.cypher.internal.compiler.v3_1.pipes.QueryState
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.api.security.SecurityContext.AUTH_DISABLED

trait QueryStateTestSupport {
  self: GraphDatabaseTestSupport =>

  def withQueryState[T](f: QueryState => T) = {
    val tx = graph.beginTransaction(KernelTransaction.Type.explicit, AUTH_DISABLED)
    try {
      val queryState = QueryStateHelper.queryStateFrom(graph, tx)
      f(queryState)
    } finally {
      tx.close()
    }
  }

  def withCountsQueryState[T](f: QueryState => T) = {
    val tx = graph.beginTransaction(KernelTransaction.Type.explicit, AUTH_DISABLED)
    try {
      val queryState = QueryStateHelper.countStats(QueryStateHelper.queryStateFrom(graph, tx))
      f(queryState)
    } finally {
      tx.close()
    }
  }

}
