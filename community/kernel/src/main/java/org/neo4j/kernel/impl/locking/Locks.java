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
package org.neo4j.kernel.impl.locking;

import org.neo4j.helpers.Service;
import org.neo4j.storageengine.api.lock.AcquireLockTimeoutException;
import org.neo4j.storageengine.api.lock.ResourceLocker;
import org.neo4j.storageengine.api.lock.ResourceType;
import org.neo4j.storageengine.api.lock.WaitStrategy;

/**
 * API for managing locks.
 *
 * Locks are grabbed by clients (which generally map to a transaction, but can be any actor in the system).
 *
 * ## Upgrading and downgrading
 *
 * Shared locks allow upgrading, and exclusive locks allow downgrading. To upgrade a held shared lock to an exclusive
 * lock, simply acquire an exclusive lock and then release the shared lock. The acquire call will block other clients
 * from acquiring shared or exclusive locks, and then wait until all other holders of the shared locks have released
 * before returning.
 *
 * Downgrading a held exclusive lock is done by acquiring a shared lock, and then releasing the exclusive lock.
 *
 * ## Lock stacking
 *
 * Each call to acquire a lock must be accompanied by a call to release that same lock. A user can call acquire on the
 * same lock multiple times, thus requiring an equal number of calls to release those locks.
 */
public interface Locks
{
    abstract class Factory extends Service
    {
        public Factory( String key, String... altKeys )
        {
            super( key, altKeys );
        }

        public abstract Locks newInstance( ResourceType[] resourceTypes );
    }

    /** For introspection and debugging. */
    interface Visitor
    {
        /** Visit the description of a lock held by at least one client. */
        void visit( ResourceType resourceType, long resourceId, String description, long estimatedWaitTime,
                long lockIdentityHashCode );
    }

    interface Client extends ResourceLocker, AutoCloseable
    {
        /**
         * Represents the fact that no lock session is used because no locks are taken.
         */
        int NO_LOCK_SESSION_ID = -1;

        /**
         * Can be grabbed when there are no locks or only share locks on a resource. If the lock cannot be acquired,
         * behavior is specified by the {@link WaitStrategy} for the given {@link ResourceType}.
         *
         * @param resourceType type or resource(s) to lock.
         * @param resourceIds id(s) of resources to lock. Multiple ids should be ordered consistently by all callers
         * of this method.
         */
        void acquireShared( ResourceType resourceType, long... resourceIds ) throws AcquireLockTimeoutException;

        @Override
        void acquireExclusive( ResourceType resourceType, long... resourceIds ) throws AcquireLockTimeoutException;

        /** Try grabbing exclusive lock, not waiting and returning a boolean indicating if we got the lock. */
        boolean tryExclusiveLock( ResourceType resourceType, long resourceId );

        /** Try grabbing shared lock, not waiting and returning a boolean indicating if we got the lock. */
        boolean trySharedLock( ResourceType resourceType, long resourceId );

        /** Release a set of shared locks */
        void releaseShared( ResourceType resourceType, long resourceId );

        /** Release a set of exclusive locks */
        void releaseExclusive( ResourceType resourceType, long resourceId );

        /**
         * Stop all active lock waiters and release them. All already held locks remains.
         * All new attempts to acquire any locks will cause exceptions.
         * This client can and should only be {@link #close() closed} afterwards.
         */
        void stop();

        /** Releases all locks, using the client after calling this is undefined. */
        @Override
        void close();

        /** For slave transactions, this tracks an identifier for the lock session running on the master */
        int getLockSessionId();
    }

    /**
     * A client is able to grab and release locks, and compete with other clients for them. This can be re-used until
     * you call {@link Locks.Client#close()}.
     *
     * @throws IllegalStateException if this instance has been closed, i.e has had {@link #close()} called.
     */
    Client newClient();

    /** Visit all held locks. */
    void accept(Visitor visitor);

    void close();
}
