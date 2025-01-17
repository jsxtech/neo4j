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
package org.neo4j.cypher.internal.compiler.v3_1.commands.expressions

import org.neo4j.cypher.internal.compiler.v3_1._
import org.neo4j.cypher.internal.compiler.v3_1.pipes.QueryState
import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_1.{ParameterWrongTypeException, CypherTypeException}
import org.neo4j.cypher.internal.frontend.v3_1.symbols._

import scala.annotation.tailrec

abstract class StringFunction(arg: Expression) extends NullInNullOutExpression(arg) {
  def innerExpectedType = CTString

  override def arguments = Seq(arg)

  override def calculateType(symbols: SymbolTable) = CTString

  override def symbolTableDependencies = arg.symbolTableDependencies
}

case object asString extends (Any => String) {

  override def apply(a: Any): String = a match {
    case null => null
    case x: String => x
    case _ => throw new CypherTypeException(
      "Expected a string value for %s, but got: %s; perhaps you'd like to cast to a string it with str()."
        .format(toString(), a.toString))
  }
}

case class ToStringFunction(argument: Expression) extends StringFunction(argument) {

  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = argument(m) match {
    case v: Number => v.toString
    case v: String => v
    case v: Boolean => v.toString
    case v =>
      throw new ParameterWrongTypeException("Expected a String, Number or Boolean, got: " + v.toString)
  }

  override def rewrite(f: (Expression) => Expression): Expression = f(ToStringFunction(argument.rewrite(f)))
}

case class ToLowerFunction(argument: Expression) extends StringFunction(argument) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = asString(argument(m)).toLowerCase

  override def rewrite(f: (Expression) => Expression) = f(ToLowerFunction(argument.rewrite(f)))
}

case class ReverseFunction(argument: Expression) extends StringFunction(argument) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = {
    val string: String = asString(argument(m))
    if (string == null) null else new java.lang.StringBuilder(string).reverse.toString
  }

  override def rewrite(f: (Expression) => Expression) = f(ReverseFunction(argument.rewrite(f)))
}

case class ToUpperFunction(argument: Expression) extends StringFunction(argument) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = asString(argument(m)).toUpperCase

  override def rewrite(f: (Expression) => Expression) = f(ToUpperFunction(argument.rewrite(f)))
}

case class LTrimFunction(argument: Expression) extends StringFunction(argument) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = asString(argument(m)).replaceAll("^\\s+", "")

  override def rewrite(f: (Expression) => Expression) = f(LTrimFunction(argument.rewrite(f)))
}

case class RTrimFunction(argument: Expression) extends StringFunction(argument) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = asString(argument(m)).replaceAll("\\s+$", "")

  override def rewrite(f: (Expression) => Expression) = f(RTrimFunction(argument.rewrite(f)))
}

case class TrimFunction(argument: Expression) extends StringFunction(argument) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = asString(argument(m)).trim

  override def rewrite(f: (Expression) => Expression) = f(TrimFunction(argument.rewrite(f)))
}

case class SubstringFunction(orig: Expression, start: Expression, length: Option[Expression])
  extends NullInNullOutExpression(orig) with NumericHelper {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = {
    val origVal = asString(orig(m))

    def noMoreThanMax(maxLength: Int, length: Int): Int =
      if (length > maxLength) {
        maxLength
      } else {
        length
      }

    // if start goes off the end of the string, let's be nice and handle that.
    val startVal = noMoreThanMax(origVal.length, asInt(start(m)))

    // if length goes off the end of the string, let's be nice and handle that.
    val lengthVal = length match {
      case None       => origVal.length - startVal
      case Some(func) => noMoreThanMax(origVal.length - startVal, asInt(func(m)))
    }

    origVal.substring(startVal, startVal + lengthVal)
  }


  override def arguments = Seq(orig, start) ++ length

  override def rewrite(f: (Expression) => Expression) = f(SubstringFunction(orig.rewrite(f), start.rewrite(f), length.map(_.rewrite(f))))

  override def calculateType(symbols: SymbolTable) = CTString

  override def symbolTableDependencies = {
    val a = orig.symbolTableDependencies ++
            start.symbolTableDependencies

    val b = length.toIndexedSeq.flatMap(_.symbolTableDependencies.toIndexedSeq).toSet

    a ++ b
  }
}

case class ReplaceFunction(orig: Expression, search: Expression, replaceWith: Expression)
  extends NullInNullOutExpression(orig) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = {
    val origVal = asString(value)
    val searchVal = asString(search(m))
    val replaceWithVal = asString(replaceWith(m))

    if (searchVal == null || replaceWithVal == null) {
      null
    } else {
      origVal.replace(searchVal, replaceWithVal)
    }
  }

  override def arguments = Seq(orig, search, replaceWith)

  override def rewrite(f: (Expression) => Expression) = f(ReplaceFunction(orig.rewrite(f), search.rewrite(f), replaceWith.rewrite(f)))

  override def calculateType(symbols: SymbolTable) = CTString

  override def symbolTableDependencies = orig.symbolTableDependencies ++
                                search.symbolTableDependencies ++
                                replaceWith.symbolTableDependencies
}
case class SplitFunction(orig: Expression, separator: Expression)
  extends NullInNullOutExpression(orig) {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = {
    val origVal = asString(orig(m))
    val separatorVal = asString(separator(m))

    if (origVal == null || separatorVal == null) {
      null
    } else {
      if (separatorVal.length > 0) {
        split(Vector.empty, origVal, 0, separatorVal)
      } else if (origVal.isEmpty) {
        Vector("")
      } else {
        origVal.sliding(1).toList
      }
    }
  }

  @tailrec
  private def split(parts: Vector[String], string: String, from: Int, separator: String): Vector[String] = {
    val index = string.indexOf(separator, from)
    if (index < 0)
      parts :+ string.substring(from)
    else
      split(parts :+ string.substring(from, index), string, index + separator.length, separator)
  }

  override def arguments = Seq(orig, separator)

  override def rewrite(f: (Expression) => Expression) = f(SplitFunction(orig.rewrite(f), separator.rewrite(f)))

  override def calculateType(symbols: SymbolTable) = CTList(CTString)

  override def symbolTableDependencies = orig.symbolTableDependencies ++ separator.symbolTableDependencies
}

case class LeftFunction(orig: Expression, length: Expression)
  extends NullInNullOutExpression(orig) with NumericHelper {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = {
    val origVal = asString(orig(m))
    val startVal = asInt(0)
    // if length goes off the end of the string, let's be nice and handle that.
    val lengthVal = if (origVal.length < asInt(length(m)) + startVal) origVal.length
    else asInt(length(m))
    origVal.substring(startVal, startVal + lengthVal)
  }

  override def arguments = Seq(orig, length)

  override def rewrite(f: (Expression) => Expression) = f(LeftFunction(orig.rewrite(f), length.rewrite(f)))

  override def calculateType(symbols: SymbolTable) = CTString

  override def symbolTableDependencies = orig.symbolTableDependencies ++
                                length.symbolTableDependencies
}

case class RightFunction(orig: Expression, length: Expression)
  extends NullInNullOutExpression(orig) with NumericHelper {
  override def compute(value: Any, m: ExecutionContext)(implicit state: QueryState): Any = {
    val origVal = asString(orig(m))
    // if length goes off the end of the string, let's be nice and handle that.
    val lengthVal = if (origVal.length < asInt(length(m))) origVal.length
    else asInt(length(m))
    val startVal = origVal.length - lengthVal
    origVal.substring(startVal, startVal + lengthVal)
  }

  override def arguments = Seq(orig, length)

  override def rewrite(f: (Expression) => Expression) = f(RightFunction(orig.rewrite(f), length.rewrite(f)))

  override def calculateType(symbols: SymbolTable) = CTString

  override def symbolTableDependencies = orig.symbolTableDependencies ++
                                length.symbolTableDependencies
}
