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
package org.neo4j.cypher.internal.compiler.v3_1.planner.logical

import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.neo4j.cypher.internal.compiler.v3_1.ast.NestedPlanExpression
import org.neo4j.cypher.internal.compiler.v3_1.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.compiler.v3_1.planner.logical.plans.{AllNodesScan, IdName, LogicalPlan, Selection}
import org.neo4j.cypher.internal.frontend.v3_1.SemanticDirection
import org.neo4j.cypher.internal.frontend.v3_1.ast._
import org.neo4j.cypher.internal.frontend.v3_1.test_helpers.CypherFunSuite

class patternExpressionRewriterTest extends CypherFunSuite with LogicalPlanningTestSupport {

  import org.mockito.Matchers._

  test("Rewrites pattern expressions") {
    // given
    val expr: Expression = And(patExpr1, patExpr2)_
    val strategy = createStrategy
    val context = newMockedLogicalPlanningContext(newMockedPlanContext, strategy = strategy)
    val rewriter = patternExpressionRewriter(Set.empty, context)

    // when
    val result = expr.endoRewrite(rewriter)

    // then
    verify( strategy ).planPatternExpression(Set.empty, patExpr1)( context )
    verify( strategy ).planPatternExpression(Set.empty, patExpr2)( context )
    verifyNoMoreInteractions( strategy )
  }

  test("Does not rewrite pattern expressions on nested plans") {
    // given
    val expr: Expression = Or(And(patExpr1, NestedPlanExpression(dummyPlan, patExpr2)_)_, patExpr3)_
    val strategy = createStrategy
    val context = newMockedLogicalPlanningContext(newMockedPlanContext, strategy = strategy)
    val rewriter = patternExpressionRewriter(Set.empty, context)

    // when
    val result = expr.endoRewrite(rewriter)

    // then
    verify( strategy ).planPatternExpression(Set.empty, patExpr1)( context )
    verify( strategy ).planPatternExpression(Set.empty, patExpr3)( context )
    verifyNoMoreInteractions( strategy )
  }

  test("Does rewrite pattern expressions inside nested plans") {
    // given
    val plan = Selection(Seq(patExpr3), dummyPlan)(solved)
    val expr: Expression = Or(And(patExpr1, NestedPlanExpression(plan, patExpr2)_)_, patExpr4)_
    val strategy = createStrategy
    val context = newMockedLogicalPlanningContext(newMockedPlanContext, strategy = strategy)
    val rewriter = patternExpressionRewriter(Set.empty, context)

    // when
    val result = expr.endoRewrite(rewriter)

    // then
    verify( strategy ).planPatternExpression(Set.empty, patExpr1)( context )
    verify( strategy ).planPatternExpression(Set.empty, patExpr3)( context )
    verify( strategy ).planPatternExpression(Set.empty, patExpr4)( context )
    verifyNoMoreInteractions( strategy )
  }

  private val patExpr1 = newPatExpr( "a", "b" )
  private val patExpr2 = newPatExpr( "c", "d" )
  private val patExpr3 = newPatExpr( "e", "f ")
  private val patExpr4 = newPatExpr( "g", "h" )

  private val dummyPlan = AllNodesScan(IdName("a"), Set.empty)(solved)

  private def newPatExpr(left: String, right: String): PatternExpression = {
    PatternExpression(RelationshipsPattern(RelationshipChain(
      NodePattern(Some(varFor(left)), Seq.empty, None) _,
      RelationshipPattern(None, optional = false, Seq.empty, None, None, SemanticDirection.OUTGOING) _,
      NodePattern(Some(varFor(right)), Seq.empty, None) _) _) _)
  }

  private def createStrategy: QueryGraphSolver = {
    val strategy = mock[QueryGraphSolver]
    when(strategy.planPatternExpression(any[Set[IdName]], any[PatternExpression])(any[LogicalPlanningContext])).thenAnswer(
      new Answer[(LogicalPlan, PatternExpression)] {
        override def answer(invocation: InvocationOnMock): (LogicalPlan, PatternExpression) = {
          val expr = invocation.getArguments()(1).asInstanceOf[PatternExpression]
          val (namedExpr, _) = PatternExpressionPatternElementNamer(expr)
          (dummyPlan, namedExpr)
        }
      })
    strategy
  }
}
