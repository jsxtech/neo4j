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
package org.neo4j.kernel.api;

import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.kernel.api.proc.CallableUserFunction;
import org.neo4j.kernel.api.security.SecurityContext;

/**
 * The main API through which access to the Neo4j kernel is made, both read
 * and write operations are supported as well as creating transactions.
 *
 * Changes to the graph (i.e. write operations) are performed via a
 * {@link #newTransaction(KernelTransaction.Type, SecurityContext) transaction context} where changes done
 * inside the transaction are visible in read operations for {@link Statement statements}
 * executed within that transaction context.
 */
public interface KernelAPI
{
    /**
     * Creates and returns a new {@link KernelTransaction} capable of modifying the
     * underlying graph.
     *
     * @param type the type of the new transaction: implicit (internally created) or explicit (created by the user)
     * @param securityContext transaction security context
     */
    KernelTransaction newTransaction( KernelTransaction.Type type, SecurityContext securityContext ) throws TransactionFailureException;

    /**
     * Creates and returns a new {@link KernelTransaction} capable of modifying the
     * underlying graph with custom timeout in milliseconds.
     *
     * @param type the type of the new transaction: implicit (internally created) or explicit (created by the user)
     * @param securityContext transaction security context
     * @param timeout transaction timeout in millisiseconds
     */
    KernelTransaction newTransaction( KernelTransaction.Type type, SecurityContext securityContext, long timeout )
            throws TransactionFailureException;

    /**
     * Registers a {@link TransactionHook} that will receive notifications about committing transactions
     * and the changes they commit.
     * @param hook {@link TransactionHook} for receiving notifications about transactions to commit.
     */
    void registerTransactionHook( TransactionHook hook );

    /**
     * Unregisters an already registered {@link TransactionHook} so that it will no longer receive notifications
     * about transactions.
     * @param hook {@link TransactionHook} to unregister.
     */
    void unregisterTransactionHook( TransactionHook hook );

    /**
     * Register a procedure that should be available from this kernel. This is not a transactional method, the procedure is not
     * durably stored, and is not propagated in a cluster.
     *
     * @param procedure procedure to register
     */
    void registerProcedure( CallableProcedure procedure ) throws ProcedureException;

    /**
     * Register a function that should be available from this kernel. This is not a transactional method, the function is not
     * durably stored, and is not propagated in a cluster.
     *
     * @param function function to register
     */
    void registerUserFunction( CallableUserFunction function ) throws ProcedureException;
}
