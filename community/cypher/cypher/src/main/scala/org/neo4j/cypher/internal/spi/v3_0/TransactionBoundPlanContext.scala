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
package org.neo4j.cypher.internal.spi.v3_0

import org.neo4j.cypher.MissingIndexException
import org.neo4j.cypher.internal.LastCommittedTxIdProvider
import org.neo4j.cypher.internal.compiler.v3_0.pipes.EntityProducer
import org.neo4j.cypher.internal.compiler.v3_0.pipes.matching.ExpanderStep
import org.neo4j.cypher.internal.compiler.v3_0.spi._
import org.neo4j.cypher.internal.frontend.v3_0.symbols.CypherType
import org.neo4j.cypher.internal.frontend.v3_0.{CypherExecutionException, symbols}
import org.neo4j.cypher.internal.spi.TransactionalContextWrapperv3_1
import org.neo4j.graphdb.Node
import org.neo4j.kernel.api.constraints.{UniquenessConstraint => KernelUniquenessConstraint}
import org.neo4j.kernel.api.exceptions.KernelException
import org.neo4j.kernel.api.exceptions.schema.SchemaKernelException
import org.neo4j.kernel.api.index.{IndexDescriptor => KernelIndexDescriptor, InternalIndexState}
import org.neo4j.kernel.api.proc.Neo4jTypes.AnyType
import org.neo4j.kernel.api.proc.{ QualifiedName, Neo4jTypes}
import org.neo4j.procedure.Mode

import scala.collection.JavaConverters._

class TransactionBoundPlanContext(tc: TransactionalContextWrapperv3_1)
  extends TransactionBoundTokenContext(tc.statement) with PlanContext with SchemaDescriptionTranslation {

  @Deprecated
  def getIndexRule(labelName: String, propertyKey: String): Option[SchemaTypes.IndexDescriptor] = evalOrNone {
    val labelId = tc.statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = tc.statement.readOperations().propertyKeyGetForName(propertyKey)

    getOnlineIndex(tc.statement.readOperations().indexGetForLabelAndPropertyKey(labelId, propertyKeyId))
  }

  def hasIndexRule(labelName: String): Boolean = {
    val labelId = tc.statement.readOperations().labelGetForName(labelName)

    val indexDescriptors = tc.statement.readOperations().indexesGetForLabel(labelId).asScala
    val onlineIndexDescriptors = indexDescriptors.flatMap(getOnlineIndex)

    onlineIndexDescriptors.nonEmpty
  }

  def getUniqueIndexRule(labelName: String, propertyKey: String): Option[SchemaTypes.IndexDescriptor] = evalOrNone {
    val labelId = tc.statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = tc.statement.readOperations().propertyKeyGetForName(propertyKey)

    // here we do not need to use getOnlineIndex method because uniqueness constraint creation is synchronous
    Some(tc.statement.readOperations().uniqueIndexGetForLabelAndPropertyKey(labelId, propertyKeyId))
  }

  private def evalOrNone[T](f: => Option[T]): Option[T] =
    try { f } catch { case _: SchemaKernelException => None }

  private def getOnlineIndex(descriptor: KernelIndexDescriptor): Option[SchemaTypes.IndexDescriptor] =
    tc.statement.readOperations().indexGetState(descriptor) match {
      case InternalIndexState.ONLINE => Some(descriptor)
      case _                         => None
    }

  def getUniquenessConstraint(labelName: String, propertyKey: String): Option[SchemaTypes.UniquenessConstraint] = try {
    val labelId = tc.statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = tc.statement.readOperations().propertyKeyGetForName(propertyKey)

    import scala.collection.JavaConverters._
    tc.statement.readOperations().constraintsGetForLabelAndPropertyKey(labelId, propertyKeyId).asScala.collectFirst {
      case unique: KernelUniquenessConstraint => unique
    }
  } catch {
    case _: KernelException => None
  }

  override def hasPropertyExistenceConstraint(labelName: String, propertyKey: String): Boolean = {
    val labelId = tc.statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = tc.statement.readOperations().propertyKeyGetForName(propertyKey)

    tc.statement.readOperations().constraintsGetForLabelAndPropertyKey(labelId, propertyKeyId).hasNext
  }

  def checkNodeIndex(idxName: String) {
    if (!tc.statement.readOperations().nodeLegacyIndexesGetAll().contains(idxName)) {
      throw new MissingIndexException(idxName)
    }
  }

  def checkRelIndex(idxName: String)  {
    if (!tc.statement.readOperations().relationshipLegacyIndexesGetAll().contains(idxName)) {
      throw new MissingIndexException(idxName)
    }
  }

  def getOrCreateFromSchemaState[T](key: Any, f: => T): T = {
    val javaCreator = new java.util.function.Function[Any, T]() {
      def apply(key: Any) = f
    }
    tc.statement.readOperations().schemaStateGetOrCreate(key, javaCreator)
  }


  // Legacy traversal matchers (pre-Ronja) (These were moved out to remove the dependency on the kernel)
  override def monoDirectionalTraversalMatcher(steps: ExpanderStep, start: EntityProducer[Node]) =
    new MonoDirectionalTraversalMatcher(steps, start)

  override def bidirectionalTraversalMatcher(steps: ExpanderStep,
                                             start: EntityProducer[Node],
                                             end: EntityProducer[Node]) =
    new BidirectionalTraversalMatcher(steps, start, end)

  val statistics: GraphStatistics =
    InstrumentedGraphStatistics(TransactionBoundGraphStatistics(tc.readOperations), new MutableGraphStatisticsSnapshot())

  val txIdProvider = LastCommittedTxIdProvider(tc.graph)

  override def procedureSignature(name: QualifiedProcedureName) = {
    val kn = new QualifiedName(name.namespace.asJava, name.name)
    val ks = tc.statement.readOperations().procedureGet(kn)
    val input = ks.inputSignature().asScala.map(s => FieldSignature(s.name(), asCypherType(s.neo4jType())))
    val output = if (ks.isVoid) None else Some(ks.outputSignature().asScala.map(s => FieldSignature(s.name(), asCypherType(s.neo4jType()))))
    val mode = asCypherProcMode(ks.mode())

    ProcedureSignature(name, input, output, mode)
  }

  private def asCypherProcMode(mode: Mode): ProcedureAccessMode = mode match {
    case Mode.READ => ProcedureReadOnlyAccess
    case Mode.DEFAULT => ProcedureReadOnlyAccess
    case Mode.WRITE => ProcedureReadWriteAccess
    case Mode.DBMS => ProcedureDbmsAccess
    case _ => throw new CypherExecutionException(
      "Unable to execute procedure, because it requires an unrecognized execution mode: " + mode.name(), null )
  }

  private def asCypherType(neoType: AnyType): CypherType = neoType match {
    case Neo4jTypes.NTString => symbols.CTString
    case Neo4jTypes.NTInteger => symbols.CTInteger
    case Neo4jTypes.NTFloat => symbols.CTFloat
    case Neo4jTypes.NTNumber => symbols.CTNumber
    case Neo4jTypes.NTBoolean => symbols.CTBoolean
    case l: Neo4jTypes.ListType => symbols.CTList(asCypherType(l.innerType()))
    case Neo4jTypes.NTMap => symbols.CTMap
    case Neo4jTypes.NTNode => symbols.CTNode
    case Neo4jTypes.NTRelationship => symbols.CTRelationship
    case Neo4jTypes.NTPath => symbols.CTPath
    case Neo4jTypes.NTPoint => symbols.CTPoint
    case Neo4jTypes.NTAny => symbols.CTAny
  }
}
