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
package org.neo4j.internal.cypher.acceptance

import org.neo4j.cypher.{ExecutionEngineFunSuite, NewPlannerTestSupport}

class AggregationAcceptanceTest extends ExecutionEngineFunSuite with NewPlannerTestSupport {

  // Non-deterministic query -- needs TCK design
  test("should aggregate using as grouping key expressions using variables in scope and nothing else") {
    val userId = createLabeledNode(Map("userId" -> 11), "User")
    relate(userId, createNode(), "FRIEND", Map("propFive" -> 1))
    relate(userId, createNode(), "FRIEND", Map("propFive" -> 3))
    relate(createNode(), userId, "FRIEND", Map("propFive" -> 2))
    relate(createNode(), userId, "FRIEND", Map("propFive" -> 4))

    val query1 = """MATCH (user:User {userId: 11})-[friendship:FRIEND]-()
                   |WITH user, collect(friendship)[toInt({param} * count(friendship))] AS selectedFriendship
                   |RETURN id(selectedFriendship) AS friendshipId, selectedFriendship.propFive AS propertyValue""".stripMargin
    val query2 = """MATCH (user:User {userId: 11})-[friendship:FRIEND]-()
                   |WITH user, collect(friendship) AS friendships
                   |WITH user, friendships[toInt({param} * size(friendships))] AS selectedFriendship
                   |RETURN id(selectedFriendship) AS friendshipId, selectedFriendship.propFive AS propertyValue""".stripMargin
    val params = "param" -> 3

    val result1 = executeWithAllPlannersAndCompatibilityMode(query1, params).toList
    val result2 = executeWithAllPlannersAndCompatibilityMode(query2, params).toList

    result1.size should equal(result2.size)
  }
}
