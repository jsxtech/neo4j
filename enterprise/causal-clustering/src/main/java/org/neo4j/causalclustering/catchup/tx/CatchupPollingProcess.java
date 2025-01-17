/*
 * Copyright (c) 2002-2019 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.catchup.tx;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.neo4j.causalclustering.catchup.CatchUpClient;
import org.neo4j.causalclustering.catchup.CatchUpClientException;
import org.neo4j.causalclustering.catchup.CatchUpResponseAdaptor;
import org.neo4j.causalclustering.catchup.storecopy.LocalDatabase;
import org.neo4j.causalclustering.catchup.storecopy.StoreCopyFailedException;
import org.neo4j.causalclustering.catchup.storecopy.StreamingTransactionsFailedException;
import org.neo4j.causalclustering.core.consensus.schedule.Timer;
import org.neo4j.causalclustering.core.consensus.schedule.TimerService;
import org.neo4j.causalclustering.core.consensus.schedule.TimerService.TimerName;
import org.neo4j.causalclustering.identity.MemberId;
import org.neo4j.causalclustering.identity.StoreId;
import org.neo4j.causalclustering.messaging.routing.CoreMemberSelectionException;
import org.neo4j.causalclustering.messaging.routing.CoreMemberSelectionStrategy;
import org.neo4j.causalclustering.catchup.storecopy.StoreCopyProcess;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.neo4j.causalclustering.catchup.tx.CatchupPollingProcess.State.PANIC;
import static org.neo4j.causalclustering.catchup.tx.CatchupPollingProcess.State.STORE_COPYING;
import static org.neo4j.causalclustering.catchup.tx.CatchupPollingProcess.State.TX_PULLING;
import static org.neo4j.causalclustering.catchup.tx.CatchupPollingProcess.Timers.TX_PULLER_TIMER;
import static org.neo4j.causalclustering.core.consensus.schedule.TimeoutFactory.fixedTimeout;
import static org.neo4j.causalclustering.core.consensus.schedule.Timer.CancelMode.SYNC_WAIT;
import static org.neo4j.kernel.impl.util.JobScheduler.Groups.pullUpdates;

/**
 * This class is responsible for pulling transactions from a core server and queuing
 * them to be applied with the {@link BatchingTxApplier}. Pull requests are issued on
 * a fixed interval.
 *
 * If the necessary transactions are not remotely available then a fresh copy of the
 * entire store will be pulled down.
 */
public class CatchupPollingProcess extends LifecycleAdapter
{
    enum Timers implements TimerName
    {
        TX_PULLER_TIMER
    }

    enum State
    {
        TX_PULLING,
        STORE_COPYING,
        PANIC
    }

    private final LocalDatabase localDatabase;
    private final Log log;
    private final Lifecycle startStopOnStoreCopy;
    private final StoreCopyProcess storeCopyProcess;
    private final Supplier<DatabaseHealth> databaseHealthSupplier;
    private final CatchUpClient catchUpClient;
    private final CoreMemberSelectionStrategy connectionStrategy;
    private final TimerService timerService;
    private final long txPullIntervalMillis;
    private final BatchingTxApplier applier;
    private final PullRequestMonitor pullRequestMonitor;

    private Timer timer;
    private volatile State state = TX_PULLING;
    private DatabaseHealth dbHealth;
    private CompletableFuture<Boolean> upToDateFuture; // we are up-to-date when we are successfully pulling
    private volatile long latestTxIdOfUpStream;

    public CatchupPollingProcess( LogProvider logProvider, LocalDatabase localDatabase,
            Lifecycle startStopOnStoreCopy, CatchUpClient catchUpClient,
            CoreMemberSelectionStrategy connectionStrategy, TimerService timerService,
            long txPullIntervalMillis, BatchingTxApplier applier, Monitors monitors,
            StoreCopyProcess storeCopyProcess, Supplier<DatabaseHealth> databaseHealthSupplier )
    {
        this.localDatabase = localDatabase;
        this.log = logProvider.getLog( getClass() );
        this.startStopOnStoreCopy = startStopOnStoreCopy;
        this.catchUpClient = catchUpClient;
        this.connectionStrategy = connectionStrategy;
        this.timerService = timerService;
        this.txPullIntervalMillis = txPullIntervalMillis;
        this.applier = applier;
        this.pullRequestMonitor = monitors.newMonitor( PullRequestMonitor.class );
        this.storeCopyProcess = storeCopyProcess;
        this.databaseHealthSupplier = databaseHealthSupplier;
    }

    @Override
    public synchronized void start() throws Throwable
    {
        timer = timerService.create( TX_PULLER_TIMER, pullUpdates, timeout -> onTimeout() );
        timer.set( fixedTimeout( txPullIntervalMillis, MILLISECONDS ) );
        dbHealth = databaseHealthSupplier.get();
        upToDateFuture = new CompletableFuture<>();
    }

    public Future<Boolean> upToDateFuture() throws InterruptedException
    {
        return upToDateFuture;
    }

    @Override
    public void stop() throws Throwable
    {
        timer.cancel( SYNC_WAIT );
    }

    public State state()
    {
        return state;
    }

    /**
     * Time to catchup!
     */
    private void onTimeout()
    {
        try
        {
            switch ( state )
            {
            case TX_PULLING:
                pullTransactions();
                break;

            case STORE_COPYING:
                copyStore();
                break;

            default:
                throw new IllegalStateException( "Tried to execute catchup but was in state " + state );
            }
        }
        catch ( Throwable e )
        {
            panic( e );
        }

        if ( state != PANIC )
        {
            timer.reset();
        }
    }

    private synchronized void panic( Throwable e )
    {
        log.error( "Unexpected issue in catchup process. No more catchup requests will be scheduled.", e );
        dbHealth.panic( e );
        upToDateFuture.completeExceptionally( e );
        state = PANIC;
    }

    private void pullTransactions()
    {
        MemberId core;
        try
        {
            core = connectionStrategy.coreMember();
        }
        catch ( CoreMemberSelectionException e )
        {
            log.warn( "Could not find core member to pull from", e );
            return;
        }

        StoreId localStoreId = localDatabase.storeId();

        boolean moreToPull = true;
        int batchCount = 1;
        while ( moreToPull )
        {
            moreToPull = pullAndApplyBatchOfTransactions( core, localStoreId, batchCount );
            batchCount++;
        }
    }

    private synchronized void handleTransaction( CommittedTransactionRepresentation tx )
    {
        if ( state == PANIC )
        {
            return;
        }

        try
        {
            applier.queue( tx );
        }
        catch ( Throwable e )
        {
            panic( e );
        }
    }

    private synchronized void streamComplete()
    {
        if ( state == PANIC )
        {
            return;
        }

        try
        {
            applier.applyBatch();
        }
        catch ( Throwable e )
        {
            panic( e );
        }
    }

    private boolean pullAndApplyBatchOfTransactions( MemberId core, StoreId localStoreId, int batchCount )
    {
        long lastQueuedTxId = applier.lastQueuedTxId();
        pullRequestMonitor.txPullRequest( lastQueuedTxId );
        TxPullRequest txPullRequest = new TxPullRequest( lastQueuedTxId, localStoreId );
        log.debug( "Pull transactions where tx id > %d [batch #%d]", lastQueuedTxId, batchCount );

        TxStreamFinishedResponse response;
        try
        {
            response = catchUpClient.makeBlockingRequest( core, txPullRequest, new CatchUpResponseAdaptor<TxStreamFinishedResponse>()
            {
                @Override
                public void onTxPullResponse( CompletableFuture<TxStreamFinishedResponse> signal, TxPullResponse response )
                {
                    handleTransaction( response.tx() );
                }

                @Override
                public void onTxStreamFinishedResponse( CompletableFuture<TxStreamFinishedResponse> signal,
                        TxStreamFinishedResponse response )
                {
                    streamComplete();
                    signal.complete( response );
                }
            } );
        }
        catch ( CatchUpClientException e )
        {
            streamComplete();
            return false;
        }

        latestTxIdOfUpStream = response.latestTxId();

        switch ( response.status() )
        {
            case SUCCESS_END_OF_BATCH:
                return true;
            case SUCCESS_END_OF_STREAM:
                log.debug( "Successfully pulled transactions from %d", lastQueuedTxId  );
                upToDateFuture.complete( true );
                return false;
            case E_TRANSACTION_PRUNED:
                log.info( "Tx pull unable to get transactions starting from %d since transactions " +
                        "have been pruned. Attempting a store copy.", lastQueuedTxId ) ;
                state = STORE_COPYING;
                return false;
            default:
                log.info( "Tx pull request unable to get transactions > %d " + lastQueuedTxId );
                return false;
        }
    }

    private void copyStore()
    {
        MemberId core;
        try
        {
            core = connectionStrategy.coreMember();
        }
        catch ( CoreMemberSelectionException e )
        {
            log.warn( "Could not find core member from which to copy store", e );
            return;
        }

        StoreId localStoreId = localDatabase.storeId();
        downloadDatabase( core, localStoreId );
    }

    private void downloadDatabase( MemberId core, StoreId localStoreId )
    {
        try
        {
            localDatabase.stopForStoreCopy();
            startStopOnStoreCopy.stop();
        }
        catch ( Throwable throwable )
        {
            throw new RuntimeException( throwable );
        }

        try
        {
            storeCopyProcess.replaceWithStoreFrom( core, localStoreId );
        }
        catch ( IOException | StoreCopyFailedException | StreamingTransactionsFailedException e )
        {
            log.warn( String.format( "Error copying store from: %s. Will retry shortly.", core ) );
            return;
        }

        try
        {
            localDatabase.start();
            startStopOnStoreCopy.start();
        }
        catch ( Throwable throwable )
        {
            throw new RuntimeException( throwable );
        }

        latestTxIdOfUpStream = 0; // we will find out on the next pull request response
        state = TX_PULLING;
        applier.refreshFromNewStore();
    }

    public String describeState()
    {
        if ( state == TX_PULLING && applier.lastQueuedTxId() > 0 && latestTxIdOfUpStream > 0 )
        {
            return format( "%s (%d of %d)", TX_PULLING.name(), applier.lastQueuedTxId(), latestTxIdOfUpStream );
        }
        else
        {
            return state.name();
        }
    }
}
