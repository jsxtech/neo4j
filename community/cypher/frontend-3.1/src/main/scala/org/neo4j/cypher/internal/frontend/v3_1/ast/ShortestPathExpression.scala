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
package org.neo4j.cypher.internal.frontend.v3_1.ast

import Expression.SemanticContext
import org.neo4j.cypher.internal.frontend.v3_1.symbols._

case class ShortestPathExpression(pattern: ShortestPaths) extends Expression with SimpleTyping {
  def position = pattern.position
  protected def possibleTypes = if (pattern.single) CTPath else CTList(CTPath)

  override def semanticCheck(ctx: SemanticContext) =
    pattern.declareVariables(Pattern.SemanticContext.Expression) chain
    pattern.semanticCheck(Pattern.SemanticContext.Expression) chain
    super.semanticCheck(ctx)
}
