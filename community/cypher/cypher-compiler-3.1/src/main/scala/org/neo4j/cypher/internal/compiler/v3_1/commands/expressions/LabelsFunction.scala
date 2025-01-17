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
package org.neo4j.cypher.internal.compiler.v3_1.commands.expressions

import org.neo4j.cypher.internal.compiler.v3_1._
import org.neo4j.cypher.internal.compiler.v3_1.executionplan.{Effects, ReadsAnyLabel}
import org.neo4j.cypher.internal.compiler.v3_1.pipes.QueryState
import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_1.ParameterWrongTypeException
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.graphdb.Node

case class LabelsFunction(nodeExpr: Expression) extends NullInNullOutExpression(nodeExpr) {

  override def compute(value: Any, m: ExecutionContext)
                      (implicit state: QueryState): Any = value match {
    case n: Node =>
      val ctx = state.query
      ctx.getLabelsForNode(n.getId).map(ctx.getLabelName).toList
    case x => throw new ParameterWrongTypeException("Expected a Node, got: " + x)
  }

  override def rewrite(f: (Expression) => Expression) = f(LabelsFunction(nodeExpr.rewrite(f)))

  override def arguments = Seq(nodeExpr)

  override def symbolTableDependencies = nodeExpr.symbolTableDependencies

  override def calculateType(symbols: SymbolTable) = {
    nodeExpr.evaluateType(CTNode, symbols)
    CTList(CTString)
  }

  override def localEffects(symbols: SymbolTable) = Effects(ReadsAnyLabel)
}
