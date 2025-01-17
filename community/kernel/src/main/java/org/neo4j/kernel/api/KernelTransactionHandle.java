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

import java.util.Optional;
import java.util.stream.Stream;

import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.security.SecurityContext;
import org.neo4j.kernel.impl.api.Kernel;

/**
 * View of a {@link KernelTransaction} that provides a limited set of actions against the transaction.
 */
public interface KernelTransactionHandle
{
    /**
     * The id of the last transaction that was committed to the store when the underlying transaction started.
     *
     * @return the committed transaction id.
     */
    long lastTransactionIdWhenStarted();

    /**
     * The timestamp of the last transaction that was committed to the store when the underlying transaction started.
     *
     * @return the timestamp value obtained with {@link System#currentTimeMillis()}.
     */
    long lastTransactionTimestampWhenStarted();

    /**
     * The start time of the underlying transaction. I.e. basically {@link System#currentTimeMillis()} when user
     * called {@link Kernel#newTransaction(KernelTransaction.Type, SecurityContext)}.
     *
     * @return the transaction start time.
     */
    long startTime();

    /**
     * Underlying transaction specific timeout. In case if timeout is 0 - transaction does not have a timeout.
     * @return transaction timeout in milliseconds, <b>0 in case if transaction does not have a timeout<b/>
     */
    long timeoutMillis();

    /**
     * Check if the underlying transaction is open.
     *
     * @return {@code true} if the underlying transaction ({@link KernelTransaction#close()} was not called),
     * {@code false} otherwise.
     */
    boolean isOpen();

    /**
     * Mark the underlying transaction for termination.
     *
     * @param reason the reason for termination.
     * @return {@code true} if the underlying transaction was marked for termination, {@code false} otherwise
     * (when this handle represents an old transaction that has been closed).
     */
    boolean markForTermination( Status reason );

    /**
     * Security context of underlying transaction that transaction has when handle was created.
     *
     * @return underlying transaction security context
     */
    SecurityContext securityContext();

    /**
     * Transaction termination reason that transaction had when handle was created.
     *
     * @return transaction termination reason.
     */
    Optional<Status> terminationReason();

    /**
     * Check if this handle points to the same underlying transaction as the given one.
     *
     * @param tx the expected transaction.
     * @return {@code true} if this handle represents {@code tx}, {@code false} otherwise.
     */
    boolean isUnderlyingTransaction( KernelTransaction tx );

    /**
     * @return a list of all queries currently executing that use the underlying transaction
     */
    Stream<ExecutingQuery> executingQueries();
}
