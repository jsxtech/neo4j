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
package synchronization;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.kernel.impl.transaction.log.rotation.LogRotation;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.test.OtherThreadExecutor.WorkerCommand;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.EmbeddedDatabaseRule;
import org.neo4j.test.rule.concurrent.OtherThreadRule;

public class TestStartTransactionDuringLogRotation
{
    @Rule
    public DatabaseRule db = new EmbeddedDatabaseRule( getClass() )
    {
        @Override
        protected GraphDatabaseBuilder newBuilder( GraphDatabaseFactory factory )
        {
            return super.newBuilder( factory ).setConfig( GraphDatabaseSettings.logical_log_rotation_threshold, "1M" );
        }
    };
    @Rule
    public final OtherThreadRule<Void> t2 = new OtherThreadRule<>( "T2-" + getClass().getName() );

    private ExecutorService executor;
    private CountDownLatch startLogRotationLatch;
    private CountDownLatch completeLogRotationLatch;
    private AtomicBoolean writerStopped;
    private Monitors monitors;
    private LogRotation.Monitor rotationListener;
    private Label label;
    private Future<Void> rotationFuture;

    @Before
    public void setUp() throws InterruptedException
    {
        executor = Executors.newCachedThreadPool();
        startLogRotationLatch = new CountDownLatch( 1 );
        completeLogRotationLatch = new CountDownLatch( 1 );
        writerStopped = new AtomicBoolean();
        monitors = db.getDependencyResolver().resolveDependency( Monitors.class );

        rotationListener = new LogRotation.Monitor()
        {
            @Override
            public void startedRotating( long currentVersion )
            {
                startLogRotationLatch.countDown();
                try
                {
                    completeLogRotationLatch.await();
                }
                catch ( InterruptedException e )
                {
                    throw new RuntimeException( e );
                }
            }

            @Override
            public void finishedRotating( long currentVersion )
            {
            }
        };

        monitors.addMonitorListener( rotationListener );
        label = Label.label( "Label" );

        rotationFuture = t2.execute( forceLogRotation( db ) );

        // Waiting for the writer task to start a log rotation
        startLogRotationLatch.await();

        // Then we should be able to start a transaction, though perhaps not be able to finish it.
        // This is what the individual test methods will be doing.
        // The test passes when transaction.close completes within the test timeout, that is, it didn't deadlock.
    }

    private WorkerCommand<Void,Void> forceLogRotation( GraphDatabaseAPI db )
    {
        return state ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                db.createNode( label ).setProperty( "a", 1 );
                tx.success();
            }

            db.getDependencyResolver().resolveDependency( LogRotation.class ).rotateLogFile();
            return null;
        };
    }

    @After
    public void tearDown() throws Exception
    {
        rotationFuture.get();
        writerStopped.set( true );
        executor.shutdown();
    }

    @Test( timeout = 10000 )
    public void logRotationMustNotObstructStartingReadTransaction() throws InterruptedException
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.getNodeById( 0 );
            tx.success();
            completeLogRotationLatch.countDown();
        }
    }

    @Test( timeout = 10000 )
    public void logRotationMustNotObstructStartingWriteTransaction() throws InterruptedException
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode();
            tx.success();
            completeLogRotationLatch.countDown();
        }
    }

    // One might be tempted to create a similar test for schema change transactions, e.g.
    // ones that do something like `db.schema().indexFor( label ).on( "a" ).create();`.
    // However, those will always fail because they will try to grab the schema write lock,
    // which they will never be able to get, because the transaction we have stuck in log
    // rotation will already be holding the schema read lock.
}
