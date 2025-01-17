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
package org.neo4j.cypher.internal.compatibility

import java.io.PrintWriter

import org.neo4j.cypher.internal.ExecutionResult
import org.neo4j.graphdb
import org.neo4j.graphdb.ResourceIterator
import org.neo4j.graphdb.Result.ResultVisitor
import org.neo4j.kernel.api.ExecutingQuery
import org.neo4j.kernel.impl.query.QueryExecutionMonitor

class ClosingExecutionResult(val query: ExecutingQuery, val inner: ExecutionResult, runSafely: RunSafely)
                            (implicit innerMonitor: QueryExecutionMonitor)
  extends ExecutionResult {

  private val monitor = OnlyOnceQueryExecutionMonitor(innerMonitor)

  // Queries with no columns are queries that do not RETURN anything.
  // In these cases, it's safe to close the results eagerly
  if (inner.columns.isEmpty)
    runSafely {
      closeIfEmpty()
    }

  override def planDescriptionRequested = runSafely {
    inner.planDescriptionRequested
  }

  override def javaIterator: graphdb.ResourceIterator[java.util.Map[String, Any]] = {
    val innerJavaIterator = inner.javaIterator

    runSafely {
      closeIfEmpty()
    }

    new graphdb.ResourceIterator[java.util.Map[String, Any]] {
      def close() = runSafely {
        endQueryExecution()
        innerJavaIterator.close()
      }

      def next() = runSafely {
        val result = innerJavaIterator.next
        closeIfEmpty()
        result
      }

      def hasNext = runSafely {
        closeIfEmpty()
        innerJavaIterator.hasNext
      }

      def remove() = runSafely {
        innerJavaIterator.remove()
      }
    }
  }

  override def columnAs[T](column: String): Iterator[T] = runSafely {
    new Iterator[T] {
      private val _inner = inner.columnAs[T](column)

      override def hasNext: Boolean = runSafely {
        closeIfEmpty()
        _inner.hasNext
      }

      override def next(): T = runSafely {
        val result = _inner.next()
        closeIfEmpty()
        result
      }
    }
  }

  override def columns = runSafely {
    inner.columns
  }

  override def javaColumns = runSafely {
    inner.javaColumns
  }

  override def queryStatistics() = runSafely { inner.queryStatistics() }

  override def dumpToString(writer: PrintWriter) = runSafely {
    inner.dumpToString(writer)
    closeIfEmpty()
  }

  override def dumpToString() = runSafely {
    val result = inner.dumpToString()
    closeIfEmpty()
    result
  }

  override def javaColumnAs[T](column: String) = runSafely {
    val _inner = inner.javaColumnAs[T](column)
    new ResourceIterator[T] {

      override def hasNext: Boolean = runSafely {
        closeIfEmpty()
        _inner.hasNext
      }

      override def next(): T = runSafely {
        val result = _inner.next()
        closeIfEmpty()
        result
      }

      override def close(): Unit = runSafely {
        _inner.close()
        endQueryExecution()
      }
    }
  }

  override def executionPlanDescription(): org.neo4j.cypher.internal.PlanDescription =
    runSafely {
      inner.executionPlanDescription()
    }

  override def close() = runSafely {
    inner.close()
    endQueryExecution()
  }

  override def next() = runSafely {
    val result = inner.next()
    closeIfEmpty()
    result
  }

  override def hasNext = runSafely {
    val next = inner.hasNext
    if (!next) {
      endQueryExecution()
    }
    next
  }

  override def executionType: graphdb.QueryExecutionType = runSafely {
    inner.executionType
  }

  override def notifications = runSafely { inner.notifications }

  override def accept[EX <: Exception](visitor: ResultVisitor[EX]) = runSafely {
    inner.accept(visitor)
    endQueryExecution()
  }

  override def toString() = runSafely {
    inner.toString()
  }

  private def closeIfEmpty(): Unit = {
    if (!inner.hasNext) {
      endQueryExecution()
    }
  }

  private def endQueryExecution() = {
    monitor.endSuccess(query) // this method is expected to be idempotent
  }
}
