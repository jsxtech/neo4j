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
package org.neo4j.cypher.internal.compiler.v3_1.executionplan

import org.neo4j.cypher.internal.compiler.v3_1.{ExecutionMode, InternalNotificationLogger}
import org.neo4j.cypher.internal.compiler.v3_1.pipes._
import org.neo4j.cypher.internal.compiler.v3_1.spi.QueryContext
import org.neo4j.cypher.internal.frontend.v3_1.CypherException

trait ExecutionResultBuilder {
  def setQueryContext(context: QueryContext)
  def setLoadCsvPeriodicCommitObserver(batchRowCount: Long)
  def setPipeDecorator(newDecorator: PipeDecorator)
  def setExceptionDecorator(newDecorator: CypherException => CypherException)
  def build(queryId: AnyRef, planType: ExecutionMode, params: Map[String, Any], notificationLogger: InternalNotificationLogger): InternalExecutionResult
}

trait ExecutionResultBuilderFactory {
  def create(): ExecutionResultBuilder
}
