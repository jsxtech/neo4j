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
package org.neo4j.cypher.internal.compiler.v3_1

import org.neo4j.cypher.ExecutionEngineFunSuite
import org.neo4j.cypher.internal.compiler.v3_1.commands.expressions.{Expression, Literal}
import org.neo4j.cypher.internal.compiler.v3_1.mutation.{CreateNode, CreateRelationship, DeleteEntityAction, RelationshipEndpoint}
import org.neo4j.cypher.internal.compiler.v3_1.pipes.{ExecuteUpdateCommandsPipe, PipeMonitor, QueryState, SingleRowPipe}
import org.neo4j.cypher.internal.compiler.v3_1.symbols.SymbolTable
import org.neo4j.cypher.internal.frontend.v3_1.CypherTypeException
import org.neo4j.cypher.internal.frontend.v3_1.symbols._
import org.neo4j.graphdb.{Node, NotFoundException}
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.api.security.AnonymousContext
import org.neo4j.kernel.impl.coreapi.InternalTransaction

import scala.collection.mutable.{Map => MutableMap}

class MutationTest extends ExecutionEngineFunSuite {

  private implicit val monitor = mock[PipeMonitor]

  def createQueryState(tx: InternalTransaction) = {

    QueryStateHelper.countStats(QueryStateHelper.queryStateFrom(graph, tx))
  }

  test("create_node") {
    val tx = graph.beginTransaction( KernelTransaction.Type.explicit, AnonymousContext.writeToken() )
    val start = SingleRowPipe()
    val createNode = new ExecuteUpdateCommandsPipe(start, Seq(CreateNode("n", Map("name" -> Literal("Andres")), Seq.empty)))

    val queryState = createQueryState(tx)
    createNode.createResults(queryState).toList

    val n = graph.getNodeById(0)
    assert(n.getProperty("name") === "Andres")
    assert(queryState.getStatistics.nodesCreated === 1)
    assert(queryState.getStatistics.propertiesSet === 1)

    tx.close()
  }

  test("join_existing_transaction_and_rollback") {
    val tx = graph.beginTransaction( KernelTransaction.Type.explicit, AnonymousContext.writeToken() )
    val start = SingleRowPipe()
    val createNode = new ExecuteUpdateCommandsPipe(start, Seq(CreateNode("n", Map("name" -> Literal("Andres")), Seq.empty)))

    createNode.createResults(createQueryState(tx)).toList

    tx.failure()
    tx.close()

    intercept[NotFoundException](graph.inTx(graph.getNodeById(1)))
  }

  test("join_existing_transaction_and_commit") {
    val tx = graph.beginTransaction( KernelTransaction.Type.explicit, AnonymousContext.writeToken() )
    val start = SingleRowPipe()
    val createNode = new ExecuteUpdateCommandsPipe(start, Seq(CreateNode("n", Map("name" -> Literal("Andres")), Seq.empty)))

    createNode.createResults(createQueryState(tx)).toList

    tx.success()
    tx.close()

    graph.inTx{
      graph.getNodeById(0).getProperty("name") should equal("Andres")
    }
  }

  private def getNode(key: String, n: Node) = InjectValue(n, CTNode)

  test("create_rel") {
    val a = createNode()
    val b = createNode()
    val tx = graph.beginTransaction( KernelTransaction.Type.explicit, AnonymousContext.writeToken() )

    val createRel = CreateRelationship("r",
      RelationshipEndpoint(getNode("a", a), Map(), Seq.empty),
      RelationshipEndpoint(getNode("b", b), Map(), Seq.empty), "REL", Map("I" -> Literal("was here")))

    val startPipe = SingleRowPipe()
    val createNodePipe = new ExecuteUpdateCommandsPipe(startPipe, Seq(createRel))

    val state = createQueryState(tx)
    val results: List[MutableMap[String, Any]] = createNodePipe.createResults(state).map(ctx => ctx.m).toList

    val r = graph.getRelationshipById(0)
    r.getProperty("I") should equal("was here")
    results should equal(List(Map("r" -> r)))
    state.getStatistics.relationshipsCreated should equal(1)
    state.getStatistics.propertiesSet should equal(1)

    tx.close()
  }

  test("throw_exception_if_wrong_stuff_to_delete") {
    val tx = graph.beginTransaction( KernelTransaction.Type.explicit, AnonymousContext.write() )
    val createRel = DeleteEntityAction(Literal("some text"), forced = false)

    intercept[CypherTypeException](createRel.exec(ExecutionContext.empty, createQueryState(tx)))
    tx.close()
  }

  test("delete_node") {
    val tx = graph.beginTransaction( KernelTransaction.Type.explicit, AnonymousContext.write() )
    val a: Node = createNode()
    val node_id: Long = a.getId
    val deleteCommand = DeleteEntityAction(getNode("a", a), forced = false)

    val startPipe = SingleRowPipe()
    val createNodePipe = new ExecuteUpdateCommandsPipe(startPipe, Seq(deleteCommand))

    val state = createQueryState(tx)
    createNodePipe.createResults(state).toList

    state.query.transactionalContext.close(success = true)
    tx.close()

    intercept[NotFoundException](graph.inTx(graph.getNodeById(node_id)))
  }
}

case class InjectValue(value:Any, typ:CypherType) extends Expression {
  def apply(v1: ExecutionContext)(implicit state: QueryState) = value

  def arguments = Seq()

  def rewrite(f: (Expression) => Expression) = this

  def calculateType(symbols: SymbolTable): CypherType = typ

  def symbolTableDependencies = Set()
}
