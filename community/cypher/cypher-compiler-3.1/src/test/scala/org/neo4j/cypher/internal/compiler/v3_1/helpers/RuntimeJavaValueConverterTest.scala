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
package org.neo4j.cypher.internal.compiler.v3_1.helpers

import org.neo4j.cypher.internal.frontend.v3_1.test_helpers.CypherFunSuite

import scala.collection.convert.Wrappers.SeqWrapper

class RuntimeJavaValueConverterTest extends CypherFunSuite {

  test("should used indexed seq when converting list") {
    val converter: RuntimeJavaValueConverter = new RuntimeJavaValueConverter(_ => false, identity)

    val converted = converter.asDeepJavaValue(List(1, 2, 3)).asInstanceOf[SeqWrapper[_]]

    converted.underlying shouldBe a [Vector[_]]
  }

  test("should used indexed seq when converting iterator") {
    val converter: RuntimeJavaValueConverter = new RuntimeJavaValueConverter(_ => false, identity)

    val converted = converter.asDeepJavaValue(List(1, 2, 3).iterator).asInstanceOf[SeqWrapper[_]]

    converted.underlying shouldBe a [Vector[_]]
  }
}
