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

import org.neo4j.cypher.internal.compiler.v3_1.commands.values.KeyToken
import org.neo4j.cypher.internal.compiler.v3_1.helpers.{IsList, IsMap}
import org.neo4j.cypher.internal.compiler.v3_1.pipes.QueryState
import org.neo4j.cypher.internal.compiler.v3_1.spi.QueryContext
import org.neo4j.graphdb.{Node, PropertyContainer, Relationship}

import scala.collection.Map

trait CypherSerializer {

  protected def serializeProperties(x: PropertyContainer, qtx: QueryContext): String = {
    val (ops, id, deleted) = x match {
      case n: Node => (qtx.nodeOps, n.getId, qtx.nodeOps.isDeletedInThisTx(n))
      case r: Relationship => (qtx.relationshipOps, r.getId, qtx.relationshipOps.isDeletedInThisTx(r))
    }

    val keyValStrings = if (deleted) Iterator("deleted")
    else ops.propertyKeyIds(id).
      map(pkId => qtx.getPropertyKeyName(pkId) + ":" + serialize(ops.getProperty(id, pkId), qtx))

    keyValStrings.mkString("{", ",", "}")
  }

  protected def serialize(a: Any, qtx: QueryContext): String = a match {
    case x: Node         => x.toString + serializeProperties(x, qtx)
    case x: Relationship => ":" + x.getType.name() + "[" + x.getId + "]" + serializeProperties(x, qtx)
    case IsMap(m)        => makeString(m, qtx)
    case IsList(coll)    => coll.map(elem => serialize(elem, qtx)).mkString("[", ",", "]")
    case x: String       => "\"" + x + "\""
    case v: KeyToken     => v.name
    case Some(x)         => x.toString
    case null            => "<null>"
    case x               => x.toString
  }

  protected def serializeWithType(x: Any)(implicit qs: QueryState) = s"${serialize(x, qs.query)}${Option(x).map(" (" +_.getClass.getSimpleName + ")").getOrElse("")}"

  private def makeString(m: QueryContext => Map[String, Any], qtx: QueryContext) = m(qtx).map {
    case (k, v) => k + " -> " + serialize(v, qtx)
  }.mkString("{", ", ", "}")

  def makeSize(txt: String, wantedSize: Int): String = {
    val actualSize = txt.length()
    if (actualSize > wantedSize) {
      txt.slice(0, wantedSize)
    } else if (actualSize < wantedSize) {
      txt + repeat(" ", wantedSize - actualSize)
    } else txt
  }

  def repeat(x: String, size: Int): String = (1 to size).map((i) => x).mkString
}
