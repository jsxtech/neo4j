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

import org.neo4j.cypher.internal.compiler.v3_1.commands.expressions.Variable
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.cypher.internal.frontend.v3_1.test_helpers.CypherFunSuite

class UnwindPipeTest extends CypherFunSuite {

  private implicit val monitor = mock[PipeMonitor]

  private def unwindWithInput(data: Traversable[Map[String, Any]]) = {
    val source = new FakePipe(data, "x" -> CTList(CTInteger))
    val unwindPipe = new UnwindPipe(source, Variable("x"), "y")()
    unwindPipe.createResults(QueryStateHelper.empty).toList
  }

  test("symbols are correct") {
    val source = new FakePipe(List.empty, "x" -> CTList(CTInteger), "something else" -> CTList(CTAny))
    val unwindPipe = new UnwindPipe(source, Variable("x"), "y")()
    unwindPipe.symbols.variables should equal(Map(
      "y" -> CTInteger,
      "something else" -> CTList(CTAny),
      "x" -> CTList(CTInteger)))
  }

  test("should unwind collection of numbers") {
    unwindWithInput(List(Map("x" -> List(1, 2)))) should equal(List(
      Map("y" -> 1, "x" -> List(1, 2)),
      Map("y" -> 2, "x" -> List(1, 2))))
  }

  test("should handle null") {
    unwindWithInput(List(Map("x" -> null))) should equal(List())
  }

  test("should handle collection of collections") {

    val listOfLists = List(
      List(1, 2, 3),
      List(4, 5, 6))

    unwindWithInput(List(Map(
      "x" -> listOfLists))) should equal(

      List(
        Map("y" -> List(1, 2, 3), "x" -> listOfLists),
        Map("y" -> List(4, 5, 6), "x" -> listOfLists)))
  }
}
