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
package org.neo4j.cypher.internal.compiler.v3_1.planner.logical.steps

import org.neo4j.cypher.internal.compiler.v3_1.planner.QueryGraph
import org.neo4j.cypher.internal.compiler.v3_1.planner.logical.{LeafPlanner, LogicalPlanningContext}
import org.neo4j.cypher.internal.frontend.v3_1.ast.{UsingScanHint, Variable}

object labelScanLeafPlanner extends LeafPlanner {
  def apply(qg: QueryGraph)(implicit context: LogicalPlanningContext) = {
    implicit val semanticTable = context.semanticTable
    val labelPredicateMap = qg.selections.labelPredicates

    for (idName <- qg.patternNodes.toIndexedSeq if !qg.argumentIds.contains(idName);
      labelPredicate <- labelPredicateMap.getOrElse(idName, Set.empty);
      labelName <- labelPredicate.labels) yield {
      val identName = idName.name
      val hint = qg.hints.collectFirst {
        case hint@UsingScanHint(Variable(`identName`), `labelName`) => hint
      }

      context.logicalPlanProducer.planNodeByLabelScan(idName, labelName, Seq(labelPredicate), hint, qg.argumentIds)
    }
  }
}
