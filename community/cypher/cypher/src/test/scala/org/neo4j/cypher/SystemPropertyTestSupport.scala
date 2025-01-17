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
package org.neo4j.cypher

import org.neo4j.cypher.internal.frontend.v3_1.test_helpers.CypherTestSupport

trait SystemPropertyTestSupport {
  self: CypherTestSupport =>

  def withSystemProperties[T](properties: (String, String)*)(f: => T) = {
    val backup = Map.newBuilder[String, String]
    try {
      properties.foreach( backup += setSystemProperty(_) )
      f
    } finally {
      backup.result().foreach( setSystemProperty )
    }
  }

  def getSystemProperty(propertyKey: String): (String, String) = (propertyKey, System.getProperty(propertyKey))

  def setSystemProperty(property: (String, String)): (String, String) = property match {
    case (k, v) => (k, System.setProperty(k, v))
  }
}
