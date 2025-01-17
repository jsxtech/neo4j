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
package org.neo4j.cypher.internal.compiler.v3_1.codegen.ir.expressions

import org.neo4j.cypher.internal.compiler.v3_1.codegen.{CodeGenContext, MethodStructure, Variable}
import org.neo4j.cypher.internal.frontend.v3_1.symbols._

case class TypeOf(relId: Variable)
  extends CodeGenExpression {

  def init[E](generator: MethodStructure[E])(implicit context: CodeGenContext) = {}

  def generateExpression[E](structure: MethodStructure[E])(implicit context: CodeGenContext) = {
    val typeName = context.namer.newVarName()
    structure.declare(typeName, CodeGenType(CTString, ReferenceType))
    if (nullable) {
      structure.ifNotStatement(structure.isNull(relId.name, CodeGenType.primitiveRel)) { body =>
        body.relType(relId.name, typeName)
      }
      structure.loadVariable(typeName)
    }
    else {
      structure.relType(relId.name, typeName)
      structure.loadVariable(typeName)
    }
  }

  override def nullable(implicit context: CodeGenContext) = relId.nullable

  override def codeGenType(implicit context: CodeGenContext) = CodeGenType(CTString, ReferenceType)
}
