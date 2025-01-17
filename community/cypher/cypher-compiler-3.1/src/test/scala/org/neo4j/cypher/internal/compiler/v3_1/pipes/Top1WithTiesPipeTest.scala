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

import org.neo4j.cypher.internal.frontend.v3_1.IncomparableValuesException
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.cypher.internal.frontend.v3_1.test_helpers.CypherFunSuite

class Top1WithTiesPipeTest extends CypherFunSuite {

  private implicit val monitor = mock[PipeMonitor]

  test("empty input gives empty output") {
    val source = new FakePipe(List(), "x" -> CTAny)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    sortPipe.createResults(QueryStateHelper.empty) should be(empty)
  }

  test("simple sorting works as expected") {
    val list = List(Map("x" -> "B"), Map("x" -> "A")).iterator
    val source = new FakePipe(list, "x" -> CTString)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    sortPipe.createResults(QueryStateHelper.empty).toList should equal(List(Map("x" -> "A")))
  }

  test("three ties for the first place are all returned") {
    val input = List(
      Map("x" -> 1, "y" -> 1),
      Map("x" -> 1, "y" -> 2),
      Map("x" -> 2, "y" -> 3),
      Map("x" -> 2, "y" -> 4)
    ).iterator

    val source = new FakePipe(input, "x" -> CTInteger, "y" -> CTInteger)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    sortPipe.createResults(QueryStateHelper.empty).toList should equal(List(
      Map("x" -> 1, "y" -> 1),
      Map("x" -> 1, "y" -> 2)))
  }

  test("if only null is present, it should be returned") {
    val input = List(
      Map[String,Any]("x" -> null, "y" -> 1),
      Map[String,Any]("x" -> null, "y" -> 2)
    ).iterator

    val source = new FakePipe(input, "x" -> CTInteger, "y" -> CTInteger)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    sortPipe.createResults(QueryStateHelper.empty).toList should equal(List(
      Map("x" -> null, "y" -> 1),
      Map("x" -> null, "y" -> 2)))
  }

  test("null should not be returned if other values are present") {
    val input = List(
      Map[String,Any]("x" -> 1, "y" -> 1),
      Map[String,Any]("x" -> null, "y" -> 2),
      Map[String,Any]("x" -> 2, "y" -> 3)
    ).iterator

    val source = new FakePipe(input, "x" -> CTInteger, "y" -> CTInteger)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    sortPipe.createResults(QueryStateHelper.empty).toList should equal(List(
      Map("x" -> 1, "y" -> 1)))
  }

  test("trying to compare arrays should fail") {
    val input = List(
      Map[String,Any]("x" -> Array(1,2), "y" -> 1),
      Map[String,Any]("x" -> Array(3,4), "y" -> 2)
    ).iterator

    val source = new FakePipe(input, "x" -> CTInteger, "y" -> CTInteger)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    intercept[IncomparableValuesException](sortPipe.createResults(QueryStateHelper.empty).toList)
  }

  test("trying to compare numbers and strings should fail") {
    val input = List(
      Map[String,Any]("x" -> 1, "y" -> 1),
      Map[String,Any]("x" -> "A", "y" -> 2)
    ).iterator

    val source = new FakePipe(input, "x" -> CTInteger, "y" -> CTInteger)
    val sortPipe = new Top1WithTiesPipe(source, List(Ascending("x")))()

    intercept[IncomparableValuesException](sortPipe.createResults(QueryStateHelper.empty).toList)
  }
}
