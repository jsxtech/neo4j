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

import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.cypher.internal.compiler.v3_1.pipes.aggregation.PercentileContFunction
import org.neo4j.cypher.internal.compiler.v3_1.pipes.aggregation.PercentileDiscFunction

case class PercentileCont(anInner: Expression, percentile: Expression) extends AggregationWithInnerExpression(anInner) {
  def createAggregationFunction = new PercentileContFunction(anInner, percentile)

  def expectedInnerType = CTNumber

  def rewrite(f: (Expression) => Expression) = f(PercentileCont(anInner.rewrite(f), percentile.rewrite(f)))

  def calculateType(symbols: SymbolTable): CypherType = {
    percentile.evaluateType(CTNumber, symbols)
    anInner.evaluateType(CTNumber, symbols)
  }
}

case class PercentileDisc(anInner: Expression, percentile: Expression) extends AggregationWithInnerExpression(anInner) {
  def createAggregationFunction = new PercentileDiscFunction(anInner, percentile)

  def expectedInnerType = CTNumber

  def rewrite(f: (Expression) => Expression) = f(PercentileDisc(anInner.rewrite(f), percentile.rewrite(f)))

  def calculateType(symbols: SymbolTable): CypherType = {
    percentile.evaluateType(CTNumber, symbols)
    anInner.evaluateType(CTNumber, symbols)
  }
}
