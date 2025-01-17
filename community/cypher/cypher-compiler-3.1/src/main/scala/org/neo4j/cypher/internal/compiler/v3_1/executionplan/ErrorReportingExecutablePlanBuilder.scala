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

import org.neo4j.cypher.internal.compiler.v3_1.planner.CantHandleQueryException
import org.neo4j.cypher.internal.compiler.v3_1.spi.PlanContext
import org.neo4j.cypher.internal.compiler.v3_1.{PreparedQuerySemantics, CompilationPhaseTracer, PreparedQuerySyntax}
import org.neo4j.cypher.internal.frontend.v3_1.InvalidArgumentException

case class ErrorReportingExecutablePlanBuilder(inner: ExecutablePlanBuilder) extends ExecutablePlanBuilder {
  override def producePlan(inputQuery: PreparedQuerySemantics, planContext: PlanContext, tracer: CompilationPhaseTracer, createFingerprintReference: (Option[PlanFingerprint]) => PlanFingerprintReference) =
    try {
      inner.producePlan(inputQuery, planContext, tracer, createFingerprintReference)
    } catch {
      case e: CantHandleQueryException =>
        throw new InvalidArgumentException("The given query is not currently supported in the selected cost-based planner", e)
    }
}
