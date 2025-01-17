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
package org.neo4j.unsafe.impl.batchimport.staging;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

import org.neo4j.unsafe.impl.batchimport.Configuration;
import org.neo4j.unsafe.impl.batchimport.executor.DynamicTaskExecutor;
import org.neo4j.unsafe.impl.batchimport.executor.ParkStrategy;
import org.neo4j.unsafe.impl.batchimport.executor.TaskExecutor;
import org.neo4j.unsafe.impl.batchimport.stats.StatsProvider;

import static java.lang.System.currentTimeMillis;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import static org.neo4j.unsafe.impl.batchimport.executor.DynamicTaskExecutor.DEFAULT_PARK_STRATEGY;
import static org.neo4j.unsafe.impl.batchimport.staging.Processing.await;

/**
 * {@link Step} that uses {@link TaskExecutor} as a queue and execution mechanism.
 * Supports an arbitrary number of threads to execute batches in parallel.
 * Subclasses implement {@link #process(Object, BatchSender)} receiving the batch to process
 * and an {@link BatchSender} for sending the modified batch, or other batches downstream.
 *
 * There's an overlap of functionality in {@link TicketedProcessing}, however the fit isn't perfect
 * for using it as the engine in a {@link ProcessorStep} because the queuing of processed results
 * works a bit differently. Perhaps sometimes this can be addressed.
 */
public abstract class ProcessorStep<T> extends AbstractStep<T>
{
    private TaskExecutor<Sender> executor;
    // max processors for this step, zero means unlimited, or rather config.maxNumberOfProcessors()
    private final int maxProcessors;
    private final Configuration config;
    private final LongPredicate catchUp = queueSizeThreshold -> queuedBatches.get() <= queueSizeThreshold;
    protected final AtomicLong begunBatches = new AtomicLong();

    // Time stamp for when we processed the last queued batch received from upstream.
    // Useful for tracking how much time we spend waiting for batches from upstream.
    private final AtomicLong lastBatchEndTime = new AtomicLong();
    private final ParkStrategy park = new ParkStrategy.Park( 1, MILLISECONDS );

    protected ProcessorStep( StageControl control, String name, Configuration config, int maxProcessors,
            StatsProvider... additionalStatsProviders )
    {
        super( control, name, config, additionalStatsProviders );
        this.config = config;
        this.maxProcessors = maxProcessors;
    }

    @Override
    public void start( int orderingGuarantees )
    {
        super.start( orderingGuarantees );
        this.executor = new DynamicTaskExecutor<>( 1, maxProcessors, theoreticalMaxProcessors(),
                DEFAULT_PARK_STRATEGY, name(), Sender::new );
    }

    private int theoreticalMaxProcessors()
    {
        return maxProcessors == 0 ? config.maxNumberOfProcessors() : maxProcessors;
    }

    @Override
    public long receive( final long ticket, final T batch )
    {
        // Don't go too far ahead
        long idleTime = await( catchUp, executor.processors( 0 ), healthChecker, park );
        incrementQueue();
        executor.submit( sender -> {
            assertHealthy();
            sender.initialize( ticket );
            try
            {
                begunBatches.incrementAndGet();
                long startTime1 = nanoTime();
                process( batch, sender );
                if ( downstream == null )
                {
                    // No batches were emmitted so we couldn't track done batches in that way.
                    // We can see that we're the last step so increment here instead
                    doneBatches.incrementAndGet();
                }
                totalProcessingTime.add( nanoTime() - startTime1 - sender.sendTime );

                decrementQueue();
                checkNotifyEndDownstream();
            }
            catch ( Throwable e )
            {
                issuePanic( e );
            }
        } );
        return idleTime;
    }

    private void decrementQueue()
    {
        // Even though queuedBatches is built into AbstractStep, in that number of received batches
        // is number of done + queued batches, this is the only implementation changing queuedBatches
        // since it's the only implementation capable of such. That's why this code is here
        // and not in AbstractStep.
        int queueSizeAfterThisJobDone = queuedBatches.decrementAndGet();
        assert queueSizeAfterThisJobDone >= 0 : "Negative queue size " + queueSizeAfterThisJobDone;
        if ( queueSizeAfterThisJobDone == 0 )
        {
            lastBatchEndTime.set( currentTimeMillis() );
        }
    }

    private void incrementQueue()
    {
        if ( queuedBatches.getAndIncrement() == 0 )
        {   // This is the first batch after we last drained the queue.
            long lastBatchEnd = lastBatchEndTime.get();
            if ( lastBatchEnd != 0 )
            {
                upstreamIdleTime.addAndGet( currentTimeMillis()-lastBatchEnd );
            }
        }
    }

    /**
     * Processes a {@link #receive(long, Object) received} batch. Any batch that should be sent downstream
     * as part of processing the supplied batch should be done so using {@link BatchSender#send(Object)}.
     *
     * The most typical implementation of this method is to process the received batch, either by
     * creating a new batch object containing some derivative of the received batch, or the batch
     * object itself with some modifications and {@link BatchSender#send(Object) emit} that in the end of the method.
     *
     * @param batch batch to process.
     * @param sender {@link BatchSender} for sending zero or more batches downstream.
     */
    protected abstract void process( T batch, BatchSender sender ) throws Throwable;

    @Override
    public void close() throws Exception
    {
        super.close();
        executor.close();
    }

    @Override
    public int processors( int delta )
    {
        return executor.processors( delta );
    }

    @SuppressWarnings( "unchecked" )
    private void sendDownstream( long ticket, Object batch )
    {
        if ( guarantees( ORDER_SEND_DOWNSTREAM ) )
        {
            await( rightDoneTicket, ticket, healthChecker, park );
        }
        downstreamIdleTime.addAndGet( downstream.receive( ticket, batch ) );
        doneBatches.incrementAndGet();
    }

    @Override
    protected void done()
    {
        lastCallForEmittingOutstandingBatches( new Sender() );
        super.done();
    }

    protected void lastCallForEmittingOutstandingBatches( BatchSender sender )
    {   // Nothing to emit, subclasses might have though
    }

    private class Sender implements BatchSender
    {
        private long sendTime;
        private long ticket;

        @Override
        public void send( Object batch )
        {
            long time = nanoTime();
            try
            {
                sendDownstream( ticket, batch );
            }
            finally
            {
                sendTime += (nanoTime() - time);
            }
        }

        public void initialize( long ticket )
        {
            this.ticket = ticket;
            this.sendTime = 0;
        }
    }
}
