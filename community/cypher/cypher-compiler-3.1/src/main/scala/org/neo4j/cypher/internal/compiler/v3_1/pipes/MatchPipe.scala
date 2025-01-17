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
import org.neo4j.cypher.internal.compiler.v3_1.pipes.matching.{MatchingContext, PatternGraph}

case class MatchPipe(source: Pipe,
                     predicates: Seq[Predicate],
                     patternGraph: PatternGraph,
                     variablesInClause: Set[String])
                    (implicit pipeMonitor: PipeMonitor) extends PipeWithSource(source, pipeMonitor) {
  val matchingContext = new MatchingContext(source.symbols, predicates, patternGraph, variablesInClause)
  val symbols = matchingContext.symbols
  val variablesBoundInSource = variablesInClause intersect source.symbols.keys.toSet

  predicates.foreach(_.registerOwningPipe(this))

  protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState) = {
    input.flatMap {
      ctx =>
        if (variablesBoundInSource.exists(i => ctx(i) == null))
          None
        else
          matchingContext.getMatches(ctx, state)
    }
  }

  override def localEffects = {
    val effects = patternGraph.effects
    if (isLeaf) effects.asLeafEffects
    else effects
  }

  override def planDescription =
    source.planDescription.andThen(this.id, matchingContext.builder.name, variables)

  def mergeStartPoint = matchingContext.builder.startPoint

  def dup(sources: List[Pipe]): Pipe = {
    val (head :: Nil) = sources
    copy(source = head)
  }
}
