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
package org.neo4j.kernel.impl.api.operations;

import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.exceptions.legacyindex.AutoIndexingKernelException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintValidationKernelException;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.impl.api.KernelStatement;

public interface EntityWriteOperations
{
    // Currently, of course, most relevant operations here are still in the old core API implementation.

    long relationshipCreate( KernelStatement statement,
            int relationshipTypeId,
            long startNodeId,
            long endNodeId ) throws EntityNotFoundException;

    long nodeCreate( KernelStatement statement );

    void nodeDelete( KernelStatement state, long nodeId )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException;

    void relationshipDelete( KernelStatement state, long relationshipId )
            throws EntityNotFoundException, InvalidTransactionTypeKernelException, AutoIndexingKernelException;

    int nodeDetachDelete( KernelStatement state, long nodeId )
            throws EntityNotFoundException, AutoIndexingKernelException, InvalidTransactionTypeKernelException,
            KernelException;

    /**
     * Labels a node with the label corresponding to the given label id.
     * If the node already had that label nothing will happen. Label ids
     * are retrieved from {@link KeyWriteOperations#labelGetOrCreateForName(org.neo4j.kernel.api.Statement, String)}
     * or {@link
     * KeyReadOperations#labelGetForName(org.neo4j.kernel.api.Statement, String)}.
     */
    boolean nodeAddLabel( KernelStatement state, long nodeId, int labelId )
            throws ConstraintValidationKernelException, EntityNotFoundException;

    /**
     * Removes a label with the corresponding id from a node.
     * If the node doesn't have that label nothing will happen. Label ids
     * are retrieved from {@link KeyWriteOperations#labelGetOrCreateForName(org.neo4j.kernel.api.Statement, String)}
     * or {@link
     * KeyReadOperations#labelGetForName(org.neo4j.kernel.api.Statement, String)}.
     */
    boolean nodeRemoveLabel( KernelStatement state, long nodeId, int labelId ) throws EntityNotFoundException;

    Property nodeSetProperty( KernelStatement state, long nodeId, DefinedProperty property )
            throws ConstraintValidationKernelException, EntityNotFoundException, AutoIndexingKernelException, InvalidTransactionTypeKernelException;

    Property relationshipSetProperty( KernelStatement state, long relationshipId, DefinedProperty property )
            throws EntityNotFoundException, AutoIndexingKernelException, InvalidTransactionTypeKernelException;

    Property graphSetProperty( KernelStatement state, DefinedProperty property );

    /**
     * Remove a node's property given the node's id and the property key id and return the value to which
     * it was set or null if it was not set on the node
     */
    Property nodeRemoveProperty( KernelStatement state, long nodeId, int propertyKeyId )
            throws EntityNotFoundException, AutoIndexingKernelException, InvalidTransactionTypeKernelException;

    Property relationshipRemoveProperty( KernelStatement state, long relationshipId, int propertyKeyId )
            throws EntityNotFoundException, AutoIndexingKernelException, InvalidTransactionTypeKernelException;

    Property graphRemoveProperty( KernelStatement state, int propertyKeyId );
}
