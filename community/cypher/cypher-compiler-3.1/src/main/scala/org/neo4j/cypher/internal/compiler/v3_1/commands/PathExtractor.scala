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
package org.neo4j.cypher.internal.compiler.v3_1.commands

import org.neo4j.cypher.internal.compiler.v3_1.ExecutionContext
import org.neo4j.cypher.internal.compiler.v3_1.commands.expressions.{Expression, PathImpl}
import org.neo4j.cypher.internal.compiler.v3_1.pipes.QueryState
import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_1.symbols.CTPath
import org.neo4j.graphdb.{Path, PropertyContainer}

import scala.collection.Map
import collection.JavaConverters._

case class PathExtractorExpression(pathPattern: Seq[Pattern]) extends Expression {

  override def apply(ctx: ExecutionContext)(implicit queryState: QueryState) = {
    def get(x: String): PropertyContainer = ctx(x).asInstanceOf[PropertyContainer]

    val firstNode = getFirstNode(pathPattern)

    val p: Seq[PropertyContainer] = pathPattern.foldLeft(get(firstNode) :: Nil)((soFar, p) => p match {
      case SingleNode(name, _, _)                => soFar :+ get(name)
      case RelatedTo(_, right, relName, _, _, _) => soFar :+ get(relName) :+ get(right.name)
      case path: PathPattern                     => getPath(ctx, path.pathName, soFar)
    })

    buildPath(p)
  }

  private def getFirstNode(pathPattern: Seq[Pattern]): String =
    pathPattern.head match {
      case RelatedTo(left, _, _, _, _, _) => left.name
      case SingleNode(name, _, _)         => name
      case path: PathPattern              => path.left.name
    }

  private def buildPath(pieces: Seq[PropertyContainer]): Path =
    if (pieces.contains(null))
      null
    else
      new PathImpl(pieces: _*)

  private def getPath(m: Map[String, Any], key: String, soFar: List[PropertyContainer]): List[PropertyContainer] = {
    val m1 = m(key)

    if (m1 == null)
      return null::Nil

    val path = m1.asInstanceOf[Path].iterator().asScala.toList
    val pathTail = if (path.head == soFar.last) {
      path.tail
    } else {
      path.reverse.tail
    }

    soFar ++ pathTail
  }

  override def rewrite(f: (Expression) => Expression) = f(this)

  override def arguments = Seq.empty

  override protected def calculateType(symbols: SymbolTable) = CTPath

  override def symbolTableDependencies =
    pathPattern.flatMap(_.possibleStartPoints).map(_._1).toSet
}

trait PathExtractor {
  def pathPattern:Seq[Pattern]
  def getPath(ctx: Map[String, Any]): Path = {
    def get(x: String): PropertyContainer = ctx(x).asInstanceOf[PropertyContainer]

    val firstNode: String = getFirstNode

    val p: Seq[PropertyContainer] = pathPattern.foldLeft(get(firstNode) :: Nil)((soFar, p) => p match {
      case SingleNode(name, _, _)                => soFar :+ get(name)
      case RelatedTo(_, right, relName, _, _, _) => soFar :+ get(relName) :+ get(right.name)
      case path: PathPattern                     => getPath(ctx, path.pathName, soFar)
    })

    buildPath(p)
  }

  private def getFirstNode[U]: String =
    pathPattern.head match {
      case RelatedTo(left, _, _, _, _, _) => left.name
      case SingleNode(name, _, _)         => name
      case path: PathPattern              => path.left.name
    }

  private def buildPath(pieces: Seq[PropertyContainer]): Path =
    if (pieces.contains(null))
      null
    else
      new PathImpl(pieces: _*)

  //WARNING: This method can return NULL
  private def getPath(m: Map[String, Any], key: String, soFar: List[PropertyContainer]): List[PropertyContainer] = {
    val m1 = m(key)

    if (m1 == null)
      return null::Nil

    val path = m1.asInstanceOf[Path].iterator().asScala.toList
    val pathTail = if (path.head == soFar.last) {
      path.tail
    } else {
      path.reverse.tail
    }

    soFar ++ pathTail
  }
}
