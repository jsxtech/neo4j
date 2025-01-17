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
package org.neo4j.cypher.internal.compiler.v3_1.pipes

import org.neo4j.cypher.internal.compiler.v3_1._
import org.neo4j.cypher.internal.compiler.v3_1.commands.predicates.Predicate
import org.neo4j.cypher.internal.compiler.v3_1.planDescription.InternalPlanDescription.Arguments.LegacyExpression

case class FilterPipe(source: Pipe, predicate: Predicate)(val estimatedCardinality: Option[Double] = None)
                     (implicit pipeMonitor: PipeMonitor) extends PipeWithSource(source, pipeMonitor) with RonjaPipe {
  val symbols = source.symbols

  predicate.registerOwningPipe(this)

  protected def internalCreateResults(input: Iterator[ExecutionContext],state: QueryState) = {
    input.filter(ctx => predicate.isTrue(ctx)(state))
  }

  def planDescriptionWithoutCardinality = source.planDescription.andThen(this.id, "Filter", variables, LegacyExpression(predicate))

  def dup(sources: List[Pipe]): Pipe = {
    val (source :: Nil) = sources
    copy(source = source)(estimatedCardinality)
  }

  override def localEffects = {
    val predicateEffects = predicate.effects(symbols)
    if (source.isLeaf) predicateEffects.asLeafEffects else predicateEffects
  }

  def withEstimatedCardinality(estimated: Double) = copy()(Some(estimated))
}
