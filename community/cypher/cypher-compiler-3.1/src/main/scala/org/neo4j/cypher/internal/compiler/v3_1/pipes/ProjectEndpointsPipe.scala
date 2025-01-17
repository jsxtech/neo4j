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

import org.neo4j.cypher.internal.compiler.v3_1.ExecutionContext
import org.neo4j.cypher.internal.compiler.v3_1.executionplan.{Effects, ReadsAllNodes}
import org.neo4j.cypher.internal.compiler.v3_1.helpers.ListSupport
import org.neo4j.cypher.internal.compiler.v3_1.planDescription.InternalPlanDescription.Arguments.KeyNames
import org.neo4j.cypher.internal.compiler.v3_1.spi.QueryContext
import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.graphdb.{Node, Relationship}

case class ProjectEndpointsPipe(source: Pipe, relName: String,
                                start: String, startInScope: Boolean,
                                end: String, endInScope: Boolean,
                                relTypes: Option[LazyTypes], directed: Boolean, simpleLength: Boolean)
                               (val estimatedCardinality: Option[Double] = None)(implicit pipeMonitor: PipeMonitor)
  extends PipeWithSource(source, pipeMonitor)
  with ListSupport
  with RonjaPipe {
  val symbols: SymbolTable =
    source.symbols.add(start, CTNode).add(end, CTNode)

  override val localEffects = if (!startInScope || !endInScope) Effects(ReadsAllNodes) else Effects()

  type Projector = (ExecutionContext) => Iterator[ExecutionContext]

  protected def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState) =
    input.flatMap(projector(state.query))

  def planDescriptionWithoutCardinality =
    source.planDescription
          .andThen(this.id, "ProjectEndpoints", variables, KeyNames(Seq(relName, start, end)))

  def dup(sources: List[Pipe]): Pipe = {
    val (source :: Nil) = sources
    copy(source = source)(estimatedCardinality)
  }

  def withEstimatedCardinality(estimated: Double) = copy()(Some(estimated))

  private def projector(qtx: QueryContext): Projector =
    if (simpleLength) project(qtx) else projectVarLength(qtx)

  private def projectVarLength(qtx: QueryContext): Projector = (context: ExecutionContext) => {
    findVarLengthRelEndpoints(context, qtx) match {
      case Some((InScopeReversed(startNode, endNode), rels)) if !directed =>
        Iterator(context.newWith3(start, endNode, end, startNode, relName, rels.reverse))
      case Some((NotInScope(startNode, endNode), rels)) if !directed =>
        Iterator(
          context.newWith2(start, startNode, end, endNode),
          context.newWith3(start, endNode, end, startNode, relName, rels.reverse)
        )
      case Some((startAndEnd, rels)) =>
        Iterator(context.newWith2(start, startAndEnd.start, end, startAndEnd.end))
      case None =>
        Iterator.empty
    }
  }

  private def project(qtx: QueryContext): Projector = (context: ExecutionContext) => {
    findSimpleLengthRelEndpoints(context, qtx) match {
      case Some(InScopeReversed(startNode, endNode)) if !directed =>
        Iterator(context.newWith2(start, endNode, end, startNode))
      case Some(NotInScope(startNode, endNode)) if !directed =>
        Iterator(
          context.newWith2(start, startNode, end, endNode),
          context.newWith2(start, endNode, end, startNode)
        )
      case Some(startAndEnd) =>
        Iterator(context.newWith2(start, startAndEnd.start, end, startAndEnd.end))
      case None =>
        Iterator.empty
    }
  }

  private def findSimpleLengthRelEndpoints(context: ExecutionContext,
                                           qtx: QueryContext
                                          ): Option[StartAndEnd] = {
    val rel = Some(context(relName).asInstanceOf[Relationship]).filter(hasAllowedType)
    rel.flatMap( rel => pickStartAndEnd(rel, rel, context, qtx) )
  }

  private def findVarLengthRelEndpoints(context: ExecutionContext,
                                        qtx: QueryContext
                                       ): Option[(StartAndEnd, Seq[Relationship])] = {
    val rels = makeTraversable(context(relName)).toIndexedSeq.asInstanceOf[Seq[Relationship]]
    if (rels.nonEmpty && rels.forall(hasAllowedType)) {
      pickStartAndEnd(rels.head, rels.last, context, qtx).map(startAndEnd => (startAndEnd, rels))
    } else {
      None
    }
  }

  private def hasAllowedType(rel: Relationship): Boolean =
    relTypes.forall(_.names.contains(rel.getType.name()))

  private def pickStartAndEnd(relStart: Relationship, relEnd: Relationship,
                              context: ExecutionContext, qtx: QueryContext): Option[StartAndEnd] = {
    val s = qtx.relationshipStartNode(relStart)
    val e = qtx.relationshipEndNode(relEnd)

    if (!startInScope && !endInScope) Some(NotInScope(s, e))
    else if ((!startInScope || context(start) == s) && (!endInScope || context(end) == e))
      Some(InScope(s, e))
    else if (!directed && (!startInScope || context(start) == e ) && (!endInScope || context(end) == s))
      Some(InScopeReversed(s, e))
    else None
  }

  sealed trait StartAndEnd {
    def start: Node
    def end: Node
  }
  case class NotInScope(start: Node, end: Node) extends StartAndEnd
  case class InScope(start: Node, end: Node) extends StartAndEnd
  case class InScopeReversed(start: Node, end: Node) extends StartAndEnd
}
