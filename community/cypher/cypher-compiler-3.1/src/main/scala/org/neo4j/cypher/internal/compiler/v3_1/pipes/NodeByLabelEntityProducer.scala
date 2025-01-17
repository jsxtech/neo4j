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
import org.neo4j.cypher.internal.compiler.v3_1.commands.NodeByLabel
import org.neo4j.cypher.internal.compiler.v3_1.planDescription.Argument
import org.neo4j.graphdb.Node

case class NodeByLabelEntityProducer(nodeByLabel: NodeByLabel, labelId: Int) extends EntityProducer[Node] {

  def apply(m: ExecutionContext, q: QueryState) = q.query.getNodesByLabel(labelId)

  override def producerType: String = nodeByLabel.producerType

  override def arguments: Seq[Argument] = nodeByLabel.arguments
}
