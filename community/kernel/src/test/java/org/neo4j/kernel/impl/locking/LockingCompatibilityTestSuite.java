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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.neo4j.storageengine.api.lock.AcquireLockTimeoutException;
import org.neo4j.storageengine.api.lock.ResourceType;
import org.neo4j.test.OtherThreadExecutor;
import org.neo4j.test.OtherThreadExecutor.WaitDetails;
import org.neo4j.test.OtherThreadExecutor.WorkerCommand;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.concurrent.OtherThreadRule;
import org.neo4j.test.runner.ParameterizedSuiteRunner;

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.fail;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.neo4j.test.rule.concurrent.OtherThreadRule.isWaiting;

/** Base for locking tests. */
@RunWith(ParameterizedSuiteRunner.class)
@Suite.SuiteClasses({
        AcquireAndReleaseLocksCompatibility.class,
        DeadlockCompatibility.class,
        LockReentrancyCompatibility.class,
        RWLockCompatibility.class,
        StopCompatibility.class,
        CloseCompatibility.class
})
public abstract class LockingCompatibilityTestSuite
{
    protected abstract Locks createLockManager();

    /**
     * Implementing this requires intricate knowledge of implementation of the particular locks client.
     * This is the most efficient way of telling whether or not a thread awaits a lock acquisition or not
     * so the price we pay for the potential fragility introduced here we gain in much snappier testing
     * when testing deadlocks and lock acquisitions.
     *
     * @param details {@link WaitDetails} gotten at a confirmed thread wait/block or similar,
     * see {@link OtherThreadExecutor}.
     * @return {@code true} if the wait details marks a wait on a lock acquisition, otherwise {@code false}
     * so that a new thread wait/block will be registered and this method called again.
     */
    protected abstract boolean isAwaitingLockAcquisition( WaitDetails details );

    public abstract static class Compatibility
    {
        @Rule
        public OtherThreadRule<Void> threadA = new OtherThreadRule<>();

        @Rule
        public OtherThreadRule<Void> threadB = new OtherThreadRule<>();

        @Rule
        public OtherThreadRule<Void> threadC = new OtherThreadRule<>();

        @Rule
        public TestDirectory testDir = TestDirectory.testDirectory( getClass() );

        protected final LockingCompatibilityTestSuite suite;

        protected Locks locks;
        protected Locks.Client clientA;
        protected Locks.Client clientB;
        protected Locks.Client clientC;

        private final Map<Locks.Client, OtherThreadRule<Void>> clientToThreadMap = new HashMap<>();

        public Compatibility( LockingCompatibilityTestSuite suite )
        {
            this.suite = suite;
        }

        @Before
        public void before()
        {
            this.locks = suite.createLockManager();
            clientA = this.locks.newClient();
            clientB = this.locks.newClient();
            clientC = this.locks.newClient();

            clientToThreadMap.put( clientA, threadA );
            clientToThreadMap.put( clientB, threadB );
            clientToThreadMap.put( clientC, threadC );
        }

        @After
        public void after()
        {
            clientA.close();
            clientB.close();
            clientC.close();
            locks.close();
            clientToThreadMap.clear();
        }

        // Utilities

        public abstract class LockCommand implements WorkerCommand<Void, Object>
        {
            private final OtherThreadRule<Void> thread;
            private final Locks.Client client;

            protected LockCommand(OtherThreadRule<Void> thread, Locks.Client client)
            {
                this.thread = thread;
                this.client = client;
            }

            public Future<Object> call()
            {
                return thread.execute( this );
            }

            public Future<Object> callAndAssertWaiting()
            {
                Future<Object> otherThreadLock = call();
                assertThat( thread, isWaiting() );
                assertFalse( "Should not have acquired lock.", otherThreadLock.isDone() );
                return otherThreadLock;
            }

            public Future<Object> callAndAssertNotWaiting()
            {
                Future<Object> run = call();
                assertNotWaiting(client, run);
                return run;
            }

            @Override
            public Object doWork( Void state ) throws Exception
            {
                doWork( client );
                return null;
            }

            abstract void doWork( Locks.Client client ) throws AcquireLockTimeoutException;

            public Locks.Client client()
            {
                return client;
            }
        }

        protected LockCommand acquireExclusive(
                final Locks.Client client,
                final ResourceType resourceType,
                final long key )
        {
            return new LockCommand(clientToThreadMap.get( client ), client)
            {
                @Override
                public void doWork( Locks.Client client ) throws AcquireLockTimeoutException
                {
                    client.acquireExclusive( resourceType, key );
                }
            };
        }

        protected LockCommand acquireShared(
                Locks.Client client,
                final ResourceType resourceType,
                final long key )
        {
            return new LockCommand(clientToThreadMap.get( client ), client)
            {
                @Override
                public void doWork( Locks.Client client ) throws AcquireLockTimeoutException
                {
                    client.acquireShared( resourceType, key );
                }
            };
        }

        protected LockCommand release(
                final Locks.Client client,
                final ResourceType resourceType,
                final long key )
        {
            return new LockCommand(clientToThreadMap.get( client ), client)
            {
                @Override
                public void doWork( Locks.Client client )
                {
                    client.releaseExclusive( resourceType, key );
                }
            };
        }

        protected void assertNotWaiting( Locks.Client client, Future<Object> lock )
        {
            try
            {
                lock.get( 5, TimeUnit.SECONDS );
            }
            catch(ExecutionException | TimeoutException | InterruptedException e)
            {
                throw new RuntimeException( "Waiting for lock timed out!" );
            }
        }

        protected void assertWaiting( Locks.Client client, Future<Object> lock )
        {
            try
            {
                lock.get(10, TimeUnit.MILLISECONDS);
                fail("Should be waiting.");
            }
            catch ( TimeoutException e )
            {
                // Ok
            }
            catch ( ExecutionException | InterruptedException e )
            {
                throw new RuntimeException( e );
            }
            assertThat( clientToThreadMap.get( client ), isWaiting() );
        }
    }
}
