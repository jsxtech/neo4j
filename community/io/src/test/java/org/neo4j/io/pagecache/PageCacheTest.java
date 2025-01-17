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
package org.neo4j.io.pagecache;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.neo4j.adversaries.RandomAdversary;
import org.neo4j.adversaries.pagecache.AdversarialPagedFile;
import org.neo4j.concurrent.BinaryLatch;
import org.neo4j.function.ThrowingConsumer;
import org.neo4j.graphdb.mockfs.DelegatingFileSystemAbstraction;
import org.neo4j.graphdb.mockfs.DelegatingStoreChannel;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.pagecache.impl.FileIsMappedException;
import org.neo4j.io.pagecache.impl.SingleFilePageSwapperFactory;
import org.neo4j.io.pagecache.randomharness.Record;
import org.neo4j.io.pagecache.randomharness.StandardRecordFormat;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.PinEvent;
import org.neo4j.test.RepeatRule;

import static java.lang.Long.toHexString;
import static java.lang.System.currentTimeMillis;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.neo4j.io.pagecache.PagedFile.PF_NO_FAULT;
import static org.neo4j.io.pagecache.PagedFile.PF_NO_GROW;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_READ_LOCK;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_WRITE_LOCK;
import static org.neo4j.test.ByteArrayMatcher.byteArray;
import static org.neo4j.test.ThreadTestUtils.fork;

@SuppressWarnings( "OptionalGetWithoutIsPresent" )
public abstract class PageCacheTest<T extends PageCache> extends PageCacheTestSupport<T>
{
    @BeforeClass
    public static void enablePinUnpinMonitoring()
    {
        DefaultPageCacheTracer.enablePinUnpinTracing();
    }

    @Test
    public void mustReportConfiguredMaxPages() throws IOException
    {
        configureStandardPageCache();
        assertThat( pageCache.maxCachedPages(), is( maxPages ) );
    }

    @Test
    public void mustReportConfiguredCachePageSize() throws IOException
    {
        configureStandardPageCache();
        assertThat( pageCache.pageSize(), is( pageCachePageSize ) );
    }

    @Test
    public void cachePageSizeMustBePowerOfTwo() throws IOException
    {
        expectedException.expect( IllegalArgumentException.class );
        getPageCache( fs, maxPages, 31, PageCacheTracer.NULL );
    }

    @Test
    public void mustHaveAtLeastTwoPages() throws Exception
    {
        expectedException.expect( IllegalArgumentException.class );
        getPageCache( fs, 1, pageCachePageSize, PageCacheTracer.NULL );
    }

    @Test
    public void mustAcceptTwoPagesAsMinimumConfiguration() throws Exception
    {
        getPageCache( fs, 2, pageCachePageSize, PageCacheTracer.NULL );
    }

    @Test
    public void mustClosePageSwapperFactoryOnPageCacheClose() throws Exception
    {
        AtomicBoolean closed = new AtomicBoolean();
        PageSwapperFactory swapperFactory = new SingleFilePageSwapperFactory()
        {
            @Override
            public void close()
            {
                closed.set( true );
            }
        };
        PageCache cache = createPageCache( swapperFactory, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        Exception exception = null;
        try
        {
            assertFalse( closed.get() );
        }
        catch ( Exception e )
        {
            exception = e;
        }
        finally
        {
            try
            {
                cache.close();
                assertTrue( closed.get() );
            }
            catch ( Exception e )
            {
                if ( exception == null )
                {
                    exception = e;
                }
                else
                {
                    exception.addSuppressed( e );
                }
            }
            if ( exception != null )
            {
                throw exception;
            }
        }
    }

    @Test
    public void closingOfPageCacheMustBeConsideredSuccessfulEvenIfPageSwapperFactoryCloseThrows() throws Exception
    {
        AtomicInteger closed = new AtomicInteger();
        PageSwapperFactory swapperFactory = new SingleFilePageSwapperFactory()
        {
            @Override
            public void close()
            {
                closed.getAndIncrement();
                throw new RuntimeException( "boo" );
            }
        };
        PageCache cache = createPageCache( swapperFactory, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try
        {
            cache.close();
            fail( "Should have thrown" );
        }
        catch ( Exception e )
        {
            assertThat( e.getMessage(), is( "boo" ) );
        }

        // We must still consider this a success, and not call PageSwapperFactory.close() again
        cache.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mustReadExistingData() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        int recordId = 0;
        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() )
            {
                verifyRecordsMatchExpected( cursor );
                recordId += recordsPerFilePage;
            }
        }

        assertThat( recordId, is( recordCount ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mustScanInTheMiddleOfTheFile() throws IOException
    {
        long startPage = 10;
        long endPage = (recordCount / recordsPerFilePage) - 10;
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        int recordId = (int) (startPage * recordsPerFilePage);
        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( startPage, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() && cursor.getCurrentPageId() < endPage )
            {
                verifyRecordsMatchExpected( cursor );
                recordId += recordsPerFilePage;
            }
        }

        assertThat( recordId, is( recordCount - (10 * recordsPerFilePage) ) );
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void writesFlushedFromPageFileMustBeExternallyObservable() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = cache.map( file( "a" ), filePageSize );

        long startPageId = 0;
        long endPageId = recordCount / recordsPerFilePage;
        try ( PageCursor cursor = pagedFile.io( startPageId, PF_SHARED_WRITE_LOCK ) )
        {
            while ( cursor.getCurrentPageId() < endPageId && cursor.next() )
            {
                do
                {
                    writeRecords( cursor );
                } while ( cursor.shouldRetry() );
            }
        }

        pagedFile.flushAndForce();

        verifyRecordsInFile( file( "a" ), recordCount );
        pagedFile.close();
    }

    @Test
    public void pageCacheFlushAndForceMustThrowOnNullIOPSLimiter() throws Exception
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        expectedException.expect( IllegalArgumentException.class );
        cache.flushAndForce( null );
    }

    @Test
    public void pagedFileFlushAndForceMustThrowOnNullIOPSLimiter() throws Exception
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pf = cache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pf.flushAndForce( null );
        }
    }

    @Test
    public void pageCacheFlushAndForceMustQueryTheGivenIOPSLimiter() throws Exception
    {
        int pagesToDirty = 10_000;
        PageCache cache = getPageCache( fs, nextPowerOf2( 2 * pagesToDirty ), pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pfA = cache.map( existingFile( "a" ), filePageSize );
        PagedFile pfB = cache.map( existingFile( "b" ), filePageSize );

        dirtyManyPages( pfA, pagesToDirty );
        dirtyManyPages( pfB, pagesToDirty );

        AtomicInteger callbackCounter = new AtomicInteger();
        AtomicInteger ioCounter = new AtomicInteger();
        cache.flushAndForce( (previousStamp, recentlyCompletedIOs, swapper) -> {
            ioCounter.addAndGet( recentlyCompletedIOs );
            return callbackCounter.getAndIncrement();
        });
        pfA.close();
        pfB.close();

        assertThat( callbackCounter.get(), greaterThan( 0 ) );
        assertThat( ioCounter.get(), greaterThanOrEqualTo( pagesToDirty * 2 - 30 ) ); // -30 because of the eviction thread
    }

    @Test
    public void pagedFileFlushAndForceMustQueryTheGivenIOPSLimiter() throws Exception
    {
        int pagesToDirty = 10_000;
        PageCache cache = getPageCache( fs, nextPowerOf2( pagesToDirty ), pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pf = cache.map( file( "a" ), filePageSize );

        // Dirty a bunch of data
        dirtyManyPages( pf, pagesToDirty );

        AtomicInteger callbackCounter = new AtomicInteger();
        AtomicInteger ioCounter = new AtomicInteger();
        pf.flushAndForce( (previousStamp, recentlyCompletedIOs, swapper) -> {
            ioCounter.addAndGet( recentlyCompletedIOs );
            return callbackCounter.getAndIncrement();
        });
        pf.close();

        assertThat( callbackCounter.get(), greaterThan( 0 ) );
        assertThat( ioCounter.get(), greaterThanOrEqualTo( pagesToDirty - 30 ) ); // -30 because of the eviction thread
    }

    private void dirtyManyPages( PagedFile pf, int pagesToDirty ) throws IOException
    {
        try ( PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < pagesToDirty; i++ )
            {
                assertTrue( cursor.next() );
            }
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void repeatablyWritesFlushedFromPageFileMustBeExternallyObservable() throws IOException
    {
        // This test exposed a race in the EphemeralFileSystemAbstraction, that made the previous
        // writesFlushedFromPageFileMustBeExternallyObservable test flaky.
        for ( int i = 0; i < 100; i++ )
        {
            tearDown();
            setUp();
            try
            {
                writesFlushedFromPageFileMustBeExternallyObservable();
            }
            catch ( Throwable e )
            {
                System.err.println( "iteration " + i );
                System.err.flush();
                throw e;
            }
        }
    }

    @Test( timeout = LONG_TIMEOUT_MILLIS )
    public void writesFlushedFromPageFileMustBeObservableEvenWhenRacingWithEviction() throws IOException
    {
        PageCache cache = getPageCache( fs, 20, pageCachePageSize, PageCacheTracer.NULL );

        long startPageId = 0;
        long endPageId = 21;
        int iterations = 10000;
        int shortsPerPage = pageCachePageSize / 2;

        try ( PagedFile pagedFile = cache.map( file( "a" ), pageCachePageSize ) )
        {
            for ( int i = 1; i <= iterations; i++ )
            {
                try ( PageCursor cursor = pagedFile.io( startPageId, PF_SHARED_WRITE_LOCK ) )
                {
                    while ( cursor.getCurrentPageId() < endPageId && cursor.next() )
                    {
                        for ( int j = 0; j < shortsPerPage; j++ )
                        {
                            cursor.putShort( (short) i );
                        }
                    }
                }

                // There are 20 pages in the cache and we've overwritten 20 pages.
                // This means eviction has probably fallen behind and is likely
                // running concurrently right now.
                // Therefor, a flush right now would have a high chance of racing
                // with eviction.
                pagedFile.flushAndForce();

                // Race or not, a flush should still put all changes in storage,
                // so we should be able to verify the contents of the file.
                try ( DataInputStream stream = new DataInputStream( fs.openAsInputStream( file( "a" ) ) ) )
                {
                    for ( int j = 0; j < shortsPerPage; j++ )
                    {
                        int value = stream.readShort();
                        assertThat( "short pos = " + j + ", iteration = " + i, value, is( i ) );
                    }
                }
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writesFlushedFromPageCacheMustBeExternallyObservable() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        long startPageId = 0;
        long endPageId = recordCount / recordsPerFilePage;
        File file = file( "a" );
        try ( PagedFile pagedFile = cache.map( file, filePageSize );
              PageCursor cursor = pagedFile.io( startPageId, PF_SHARED_WRITE_LOCK ) )
        {
            while ( cursor.getCurrentPageId() < endPageId && cursor.next() )
            {
                do
                {
                    writeRecords( cursor );
                } while ( cursor.shouldRetry() );
            }
        } // closing the PagedFile implies flushing because it was the last reference

        verifyRecordsInFile( file, recordCount );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writesToPagesMustNotBleedIntoAdjacentPages() throws IOException
    {
        configureStandardPageCache();

        // Write the pageId+1 to every byte in the file
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 1; i <= 100; i++ )
            {
                assertTrue( cursor.next() );
                for ( int j = 0; j < filePageSize; j++ )
                {
                    cursor.putByte( (byte) i );
                }
            }
        }

        // Then check that none of those writes ended up in adjacent pages
        InputStream inputStream = fs.openAsInputStream( file( "a" ) );
        for ( int i = 1; i <= 100; i++ )
        {
            for ( int j = 0; j < filePageSize; j++ )
            {
                assertThat( inputStream.read(), is( i ) );
            }
        }
        inputStream.close();
    }

    @Test
    public void channelMustBeForcedAfterPagedFileFlushAndForce() throws Exception
    {
        final AtomicInteger writeCounter = new AtomicInteger();
        final AtomicInteger forceCounter = new AtomicInteger();
        FileSystemAbstraction fs = writeAndForceCountingFs( writeCounter, forceCounter );

        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.putInt( 1 );
                assertTrue( cursor.next() );
                cursor.putInt( 1 );
            }

            pagedFile.flushAndForce();

            assertThat( writeCounter.get(), greaterThanOrEqualTo( 2 ) ); // we might race with background flushing
            assertThat( forceCounter.get(), is( 1 ) );
        }
    }

    @Test
    public void channelsMustBeForcedAfterPageCacheFlushAndForce() throws Exception
    {
        final AtomicInteger writeCounter = new AtomicInteger();
        final AtomicInteger forceCounter = new AtomicInteger();
        FileSystemAbstraction fs = writeAndForceCountingFs( writeCounter, forceCounter );

        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try ( PagedFile pagedFileA = pageCache.map( existingFile( "a" ), filePageSize );
              PagedFile pagedFileB = pageCache.map( existingFile( "b" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFileA.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.putInt( 1 );
                assertTrue( cursor.next() );
                cursor.putInt( 1 );
            }
            try ( PageCursor cursor = pagedFileB.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.putInt( 1 );
            }

            pageCache.flushAndForce();

            assertThat( writeCounter.get(), greaterThanOrEqualTo( 3 ) ); // we might race with background flushing
            assertThat( forceCounter.get(), is( 2 ) );
        }
    }

    private DelegatingFileSystemAbstraction writeAndForceCountingFs( final AtomicInteger writeCounter,
                                                                     final AtomicInteger forceCounter )
    {
        return new DelegatingFileSystemAbstraction( fs )
        {
            @Override
            public StoreChannel open( File fileName, String mode ) throws IOException
            {
                return new DelegatingStoreChannel( super.open( fileName, mode ) )
                {
                    @Override
                    public void writeAll( ByteBuffer src, long position ) throws IOException
                    {
                        writeCounter.getAndIncrement();
                        super.writeAll( src, position );
                    }

                    @Override
                    public void force( boolean metaData ) throws IOException
                    {
                        forceCounter.getAndIncrement();
                        super.force( metaData );
                    }
                };
            }
        };
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void firstNextCallMustReturnFalseWhenTheFileIsEmptyAndNoGrowIsSpecified() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            assertFalse( cursor.next() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void nextMustReturnTrueThenFalseWhenThereIsOnlyOnePageInTheFileAndNoGrowIsSpecified() throws IOException
    {
        int numberOfRecordsToGenerate = recordsPerFilePage; // one page worth
        generateFileWithRecords( file( "a" ), numberOfRecordsToGenerate, recordSize );

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            assertTrue( cursor.next() );
            verifyRecordsMatchExpected( cursor );
            assertFalse( cursor.next() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void closingWithoutCallingNextMustLeavePageUnpinnedAndUntouched() throws IOException
    {
        int numberOfRecordsToGenerate = recordsPerFilePage; // one page worth
        generateFileWithRecords( file( "a" ), numberOfRecordsToGenerate, recordSize );

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            //noinspection EmptyTryBlock
            try ( PageCursor ignore = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                // No call to next, so the page should never get pinned in the first place, nor
                // should the page corruption take place.
            }

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
            {
                // We didn't call next before, so the page and its records should still be fine
                cursor.next();
                verifyRecordsMatchExpected( cursor );
            }
        }
    }

    @Test
    public void nextWithNegativeInitialPageIdMustReturnFalse() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            try ( PageCursor cursor = pf.io( -1, PF_SHARED_WRITE_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
            try ( PageCursor cursor = pf.io( -1, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
        }
    }

    @Test
    public void nextWithNegativePageIdMustReturnFalse() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            long pageId = 12;
            try ( PageCursor cursor = pf.io( pageId, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next( -1 ) );
                assertThat( cursor.getCurrentPageId(), is( PageCursor.UNBOUND_PAGE_ID ) );
            }
            try ( PageCursor cursor = pf.io( pageId, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next( -1 ) );
                assertThat( cursor.getCurrentPageId(), is( PageCursor.UNBOUND_PAGE_ID ) );
            }
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void rewindMustStartScanningOverFromTheBeginning() throws IOException
    {
        int numberOfRewindsToTest = 10;
        generateFileWithRecords( file( "a" ), recordCount, recordSize );
        int actualPageCounter = 0;
        int filePageCount = recordCount / recordsPerFilePage;
        int expectedPageCounterResult = numberOfRewindsToTest * filePageCount;

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            for ( int i = 0; i < numberOfRewindsToTest; i++ )
            {
                while ( cursor.next() )
                {

                    verifyRecordsMatchExpected( cursor );
                    actualPageCounter++;
                }
                cursor.rewind();
            }
        }

        assertThat( actualPageCounter, is( expectedPageCounterResult ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mustCloseFileChannelWhenTheLastHandleIsUnmapped() throws Exception
    {
        assumeTrue( "This depends on EphemeralFSA specific features",
                fs.getClass() == EphemeralFileSystemAbstraction.class );

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile a = cache.map( file( "a" ), filePageSize );
        PagedFile b = cache.map( file( "a" ), filePageSize );
        a.close();
        b.close();
        ((EphemeralFileSystemAbstraction) fs).assertNoOpenFiles();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void dirtyPagesMustBeFlushedWhenTheCacheIsClosed() throws IOException
    {
        configureStandardPageCache();

        long startPageId = 0;
        long endPageId = recordCount / recordsPerFilePage;
        File file = file( "a" );
        try ( PagedFile pagedFile = pageCache.map( file, filePageSize );
              PageCursor cursor = pagedFile.io( startPageId, PF_SHARED_WRITE_LOCK ) )
        {
            while ( cursor.getCurrentPageId() < endPageId && cursor.next() )
            {
                do
                {
                    writeRecords( cursor );
                } while ( cursor.shouldRetry() );
            }
        }
        finally
        {
            //noinspection ThrowFromFinallyBlock
            pageCache.close();
        }

        verifyRecordsInFile( file, recordCount );
    }

    @RepeatRule.Repeat( times = 100 )
    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void flushingDuringPagedFileCloseMustRetryUntilItSucceeds() throws IOException
    {
        FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs )
        {
            @Override
            public StoreChannel open( File fileName, String mode ) throws IOException
            {
                return new DelegatingStoreChannel( super.open( fileName, mode ) )
                {
                    private int writeCount = 0;

                    @Override
                    public void writeAll( ByteBuffer src, long position ) throws IOException
                    {
                        if ( writeCount++ < 10 )
                        {
                            throw new IOException( "This is a benign exception that we expect to be thrown " +
                                                   "during a flush of a PagedFile." );
                        }
                        super.writeAll( src, position );
                    }
                };
            }
        };
        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PrintStream oldSystemErr = System.err;

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            writeRecords( cursor );

            // Silence any stack traces the failed flushes might print.
            System.setErr( new PrintStream( new ByteArrayOutputStream() ) );
        }
        finally
        {
            System.setErr( oldSystemErr );
        }

        verifyRecordsInFile( file( "a" ), recordsPerFilePage );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFilesInClosedCacheMustThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        cache.close();
        expectedException.expect( IllegalStateException.class );
        cache.map( file( "a" ), filePageSize );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void flushingClosedCacheMustThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        cache.close();
        expectedException.expect( IllegalStateException.class );
        cache.flushAndForce();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileWithPageSizeGreaterThanCachePageSizeMustThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        expectedException.expect( IllegalArgumentException.class );
        cache.map( file( "a" ), pageCachePageSize + 1 ); // this must throw
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileWithPageSizeSmallerThanLongSizeBytesMustThrow() throws IOException
    {
        // Because otherwise we cannot ensure that our branch-free bounds checking always lands within a page boundary.
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        expectedException.expect( IllegalArgumentException.class );
        cache.map( file( "a" ), Long.BYTES - 1 ); // this must throw
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileWithPageSizeSmallerThanLongSizeBytesMustThrowEvenWithAnyPageSizeOpenOptionAndNoExistingMapping()
            throws IOException
    {
        // Because otherwise we cannot ensure that our branch-free bounds checking always lands within a page boundary.
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        expectedException.expect( IllegalArgumentException.class );
        cache.map( file( "a" ), Long.BYTES - 1, PageCacheOpenOptions.ANY_PAGE_SIZE ); // this must throw
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileWithPageZeroPageSizeMustThrowEvenWithExistingMapping() throws Exception
    {
        configureStandardPageCache();
        File file = file( "a" );
        //noinspection unused
        try ( PagedFile oldMapping = pageCache.map( file, filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pageCache.map( file, Long.BYTES - 1 ); // this must throw
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileWithPageZeroPageSizeAndAnyPageSizeOpenOptionMustNotThrowGivenExistingMapping()
            throws Exception
    {
        configureStandardPageCache();
        File file = file( "a" );
        //noinspection unused,EmptyTryBlock
        try ( PagedFile oldMapping = pageCache.map( file, filePageSize );
              PagedFile newMapping = pageCache.map( file, 0, PageCacheOpenOptions.ANY_PAGE_SIZE ) )
        {
            // All good
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileWithPageSizeEqualToCachePageSizeMustNotThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = cache.map( file( "a" ), pageCachePageSize );// this must NOT throw
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void notSpecifyingAnyPfFlagsMustThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pagedFile.io( 0, 0 ); // this must throw
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void notSpecifyingAnyPfLockFlagsMustThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pagedFile.io( 0, PF_NO_FAULT ); // this must throw
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void specifyingBothReadAndWriteLocksMustThrow() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_SHARED_READ_LOCK ); // this must throw
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mustNotPinPagesAfterNextReturnsFalse() throws Exception
    {
        final CountDownLatch startLatch = new CountDownLatch( 1 );
        final CountDownLatch unpinLatch = new CountDownLatch( 1 );
        final AtomicReference<Exception> exceptionRef = new AtomicReference<>();
        generateFileWithRecords( file( "a" ), recordsPerFilePage, recordSize );
        final PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        final PagedFile pagedFile = cache.map( file( "a" ), filePageSize );

        Runnable runnable = () -> {
            try ( PageCursor cursorA = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursorA.next() );
                assertFalse( cursorA.next() );
                startLatch.countDown();
                unpinLatch.await();
                cursorA.close();
            }
            catch ( Exception e )
            {
                exceptionRef.set( e );
            }
        };
        executor.submit( runnable );

        startLatch.await();
        try ( PageCursor cursorB = pagedFile.io( 1, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursorB.next() );
            unpinLatch.countDown();
        }
        finally
        {
            //noinspection ThrowFromFinallyBlock
            pagedFile.close();
        }
        Exception e = exceptionRef.get();
        if ( e != null )
        {
            throw new Exception( "Child thread got exception", e );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void nextMustResetTheCursorOffset() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = cache.map( file( "a" ), filePageSize );

        try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            do
            {
                cursor.setOffset( 0 );
                cursor.putByte( (byte) 1 );
                cursor.putByte( (byte) 2 );
                cursor.putByte( (byte) 3 );
                cursor.putByte( (byte) 4 );
            } while ( cursor.shouldRetry() );
            assertTrue( cursor.next() );
            do
            {
                cursor.setOffset( 0 );
                cursor.putByte( (byte) 5 );
                cursor.putByte( (byte) 6 );
                cursor.putByte( (byte) 7 );
                cursor.putByte( (byte) 8 );
            } while ( cursor.shouldRetry() );
        }

        try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK ) )
        {
            byte[] bytes = new byte[4];
            assertTrue( cursor.next() );
            do
            {
                cursor.getBytes( bytes );
            } while ( cursor.shouldRetry() );
            assertThat( bytes, byteArray( new byte[]{1, 2, 3, 4} ) );
            assertTrue( cursor.next() );
            do
            {
                cursor.getBytes( bytes );
            } while ( cursor.shouldRetry() );
            assertThat( bytes, byteArray( new byte[]{5, 6, 7, 8} ) );
        }
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void nextMustAdvanceCurrentPageId() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentPageId(), is( 0L ) );
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentPageId(), is( 1L ) );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void nextToSpecificPageIdMustAdvanceFromThatPointOn() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentPageId(), is( 1L ) );
            assertTrue( cursor.next( 4L ) );
            assertThat( cursor.getCurrentPageId(), is( 4L ) );
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentPageId(), is( 5L ) );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void currentPageIdIsUnboundBeforeFirstNextAndAfterRewind() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK ) )
        {
            assertThat( cursor.getCurrentPageId(), is( PageCursor.UNBOUND_PAGE_ID ) );
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentPageId(), is( 0L ) );
            cursor.rewind();
            assertThat( cursor.getCurrentPageId(), is( PageCursor.UNBOUND_PAGE_ID ) );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void pageCursorMustKnowCurrentFilePageSize() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK ) )
        {
            assertThat( cursor.getCurrentPageSize(), is( PageCursor.UNBOUND_PAGE_SIZE ) );
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentPageSize(), is( filePageSize ) );
            cursor.rewind();
            assertThat( cursor.getCurrentPageSize(), is( PageCursor.UNBOUND_PAGE_SIZE ) );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void pageCursorMustKnowCurrentFile() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK ) )
        {
            assertThat( cursor.getCurrentFile(), nullValue() );
            assertTrue( cursor.next() );
            assertThat( cursor.getCurrentFile(), is( file( "a" ) ) );
            cursor.rewind();
            assertThat( cursor.getCurrentFile(), nullValue() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readingFromUnboundReadCursorMustThrow() throws IOException
    {
        verifyOnReadCursor( this::checkUnboundReadCursorAccess );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readingFromUnboundWriteCursorMustThrow() throws IOException
    {
        verifyOnReadCursor( this::checkUnboundWriteCursorAccess );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readingFromPreviouslyBoundCursorMustThrow() throws IOException
    {
        verifyOnReadCursor( this::checkPreviouslyBoundWriteCursorAccess );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writingToUnboundCursorMustThrow() throws IOException
    {
        verifyOnWriteCursor( this::checkUnboundWriteCursorAccess );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writingToPreviouslyBoundCursorMustThrow() throws IOException
    {
        verifyOnWriteCursor( this::checkPreviouslyBoundWriteCursorAccess );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readFromReadCursorAfterNextReturnsFalseMustThrow() throws Exception
    {
        verifyOnReadCursor( this::checkReadCursorAfterFailedNext );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readFromPreviouslyBoundReadCursorAfterNextReturnsFalseMustThrow() throws Exception
    {
        verifyOnReadCursor( this::checkPreviouslyBoundReadCursorAfterFailedNext );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readFromWriteCursorAfterNextReturnsFalseMustThrow() throws Exception
    {
        verifyOnReadCursor( this::checkWriteCursorAfterFailedNext );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readFromPreviouslyBoundWriteCursorAfterNextReturnsFalseMustThrow() throws Exception
    {
        verifyOnReadCursor( this::checkPreviouslyBoundWriteCursorAfterFailedNext );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeAfterNextReturnsFalseMustThrow() throws Exception
    {
        verifyOnWriteCursor( this::checkWriteCursorAfterFailedNext );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeToPreviouslyBoundCursorAfterNextReturnsFalseMustThrow() throws Exception
    {
        verifyOnWriteCursor( this::checkPreviouslyBoundWriteCursorAfterFailedNext );
    }

    @Test
    public void tryMappedPagedFileShouldReportMappedFilePresent() throws Exception
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        final File file = file( "a" );
        try ( PagedFile pf = cache.map( file, filePageSize ) )
        {
            final Optional<PagedFile> optional = cache.getExistingMapping( file );
            assertTrue( optional.isPresent() );
            final PagedFile actual = optional.get();
            assertThat( actual, sameInstance( pf ) );
            actual.close();
        }
    }

    @Test
    public void tryMappedPagedFileShouldReportNonMappedFileNotPresent() throws Exception
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        final Optional<PagedFile> dont_exist = cache.getExistingMapping( new File( "dont_exist" ) );
        assertFalse( dont_exist.isPresent() );
    }

    private void verifyOnReadCursor(
            ThrowingConsumer<PageCursorAction,IOException> testTemplate ) throws IOException
    {
        testTemplate.accept( PageCursor::getByte );
        testTemplate.accept( PageCursor::getInt );
        testTemplate.accept( PageCursor::getLong );
        testTemplate.accept( PageCursor::getShort );
        testTemplate.accept( ( cursor ) -> cursor.getByte( 0 ) );
        testTemplate.accept( ( cursor ) -> cursor.getInt( 0 ) );
        testTemplate.accept( ( cursor ) -> cursor.getLong( 0 ) );
        testTemplate.accept( ( cursor ) -> cursor.getShort( 0 ) );
    }

    private void verifyOnWriteCursor(
            ThrowingConsumer<PageCursorAction,IOException> testTemplate ) throws IOException
    {
        testTemplate.accept( ( cursor ) -> cursor.putByte( (byte) 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putInt( 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putLong( 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putShort( (short) 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putByte( 0, (byte) 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putInt( 0, 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putLong( 0, 1 ) );
        testTemplate.accept( ( cursor ) -> cursor.putShort( 0, (short) 1 ) );
    }

    private void checkUnboundReadCursorAccess( PageCursorAction action ) throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );

        }
    }

    private void checkUnboundWriteCursorAccess( PageCursorAction action ) throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );
        }
    }

    private void checkPreviouslyBoundWriteCursorAccess( PageCursorAction action ) throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( cursor.next() );
            action.apply( cursor );
            assertFalse( cursor.checkAndClearBoundsFlag() );
            cursor.close();
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );
        }
    }

    private void checkReadCursorAfterFailedNext( PageCursorAction action ) throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertFalse( cursor.next() );
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );
        }
    }

    private void checkPreviouslyBoundReadCursorAfterFailedNext( PageCursorAction action )
            throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
        }

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( cursor.next() );
            assertFalse( cursor.next() );
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );
        }
    }

    private void checkWriteCursorAfterFailedNext( PageCursorAction action ) throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            assertFalse( cursor.next() );
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );
        }
    }

    private void checkPreviouslyBoundWriteCursorAfterFailedNext( PageCursorAction action )
            throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
        }

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            assertTrue( cursor.next() );
            assertFalse( cursor.next() );
            action.apply( cursor );
            assertTrue( cursor.checkAndClearBoundsFlag() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void lastPageMustBeAccessibleWithNoGrowSpecified() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursor.next() );
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 2L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 2L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 3L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 3L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void lastPageMustBeAccessibleWithNoGrowSpecifiedEvenIfLessThanFilePageSize() throws IOException
    {
        generateFileWithRecords( file( "a" ), (recordsPerFilePage * 2) - 1, recordSize );

        configureStandardPageCache();
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursor.next() );
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 2L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 2L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 3L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 3L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void firstPageMustBeAccessibleWithNoGrowSpecifiedIfItIsTheOnlyPage() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage, recordSize );

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void firstPageMustBeAccessibleEvenIfTheFileIsNonEmptyButSmallerThanFilePageSize() throws IOException
    {
        generateFileWithRecords( file( "a" ), 1, recordSize );

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void firstPageMustNotBeAccessibleIfFileIsEmptyAndNoGrowSpecified() throws IOException
    {
        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try ( PagedFile pagedFile = cache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next() );
            }

            try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next() );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void newlyWrittenPagesMustBeAccessibleWithNoGrow() throws IOException
    {
        int initialPages = 1;
        int pagesToAdd = 3;
        generateFileWithRecords( file( "a" ), recordsPerFilePage * initialPages, recordSize );

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = cache.map( file( "a" ), filePageSize );

        try ( PageCursor cursor = pagedFile.io( 1L, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < pagesToAdd; i++ )
            {
                assertTrue( cursor.next() );
                do
                {
                    writeRecords( cursor );
                } while ( cursor.shouldRetry() );
            }
        }

        int pagesChecked = 0;
        try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            while ( cursor.next() )
            {
                verifyRecordsMatchExpected( cursor );
                pagesChecked++;
            }
        }
        assertThat( pagesChecked, is( initialPages + pagesToAdd ) );

        pagesChecked = 0;
        try ( PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() )
            {
                verifyRecordsMatchExpected( cursor );
                pagesChecked++;
            }
        }
        assertThat( pagesChecked, is( initialPages + pagesToAdd ) );
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readLockImpliesNoGrow() throws IOException
    {
        int initialPages = 3;
        generateFileWithRecords( file( "a" ), recordsPerFilePage * initialPages, recordSize );

        configureStandardPageCache();

        int pagesChecked = 0;
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0L, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() )
            {
                pagesChecked++;
            }
        }
        assertThat( pagesChecked, is( initialPages ) );
    }

    // This test has an internal timeout in that it tries to verify 1000 reads within SHORT_TIMEOUT_MILLIS,
    // although this is a soft limit in that it may abort if number of verifications isn't reached.
    // This is so because on some machines this test takes a very long time to run. Verifying in the end
    // that at least there were some correct reads is good enough.
    @Test
    public void retryMustResetCursorOffset() throws Exception
    {
        // The general idea here, is that we have a page with a particular value in its 0th position.
        // We also have a thread that constantly writes to the middle of the page, so it modifies
        // the page, but does not change the value in the 0th position. This thread will in principle
        // mean that it is possible for a reader to get an inconsistent view and must retry.
        // We then check that every retry iteration will read the special value in the 0th position.
        // We repeat the experiment a couple of times to make sure we didn't succeed by chance.

        PageCache cache = getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        final PagedFile pagedFile = cache.map( file( "a" ), filePageSize );
        final AtomicReference<Exception> caughtWriterException = new AtomicReference<>();
        final CountDownLatch startLatch = new CountDownLatch( 1 );
        final byte expectedByte = (byte) 13;

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            if ( cursor.next() )
            {
                do
                {
                    cursor.putByte( expectedByte );
                    // Give some hint to scheduler to give some CPU cycles to the read thread below
                    Thread.yield();
                } while ( cursor.shouldRetry() );
            }
        }

        AtomicBoolean end = new AtomicBoolean( false );
        Runnable writer = () -> {
            while ( !end.get() )
            {
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
                {
                    if ( cursor.next() )
                    {
                        do
                        {
                            cursor.setOffset( recordSize );
                            cursor.putByte( (byte) 14 );
                        } while ( cursor.shouldRetry() );
                    }
                    startLatch.countDown();
                }
                catch ( IOException e )
                {
                    caughtWriterException.set( e );
                    throw new RuntimeException( e );
                }
            }
        };
        Future<?> writerFuture = executor.submit( writer );

        startLatch.await();

        long timeout = currentTimeMillis() + SHORT_TIMEOUT_MILLIS;
        int i = 0;
        for ( ; i < 1000 && currentTimeMillis() < timeout; i++ )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                do
                {
                    assertThat( cursor.getByte(), is( expectedByte ) );
                } while ( cursor.shouldRetry() && currentTimeMillis() < timeout );
            }
        }

        end.set( true );
        writerFuture.get();
        assertTrue( i > 1 );
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void nextWithPageIdMustAllowTraversingInReverse() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );
        long lastFilePageId = (recordCount / recordsPerFilePage) - 1;

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            for ( long currentPageId = lastFilePageId; currentPageId >= 0; currentPageId-- )
            {
                assertTrue( "next( currentPageId = " + currentPageId + " )",
                        cursor.next( currentPageId ) );
                assertThat( cursor.getCurrentPageId(), is( currentPageId ) );
                verifyRecordsMatchExpected( cursor );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void nextWithPageIdMustReturnFalseIfPageIdIsBeyondFilePageRangeAndNoGrowSpecified() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                assertFalse( cursor.next( 2 ) );
                assertTrue( cursor.next( 1 ) );
            }

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertFalse( cursor.next( 2 ) );
                assertTrue( cursor.next( 1 ) );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void pagesAddedWithNextWithPageIdMustBeAccessibleWithNoGrowSpecified() throws IOException
    {
        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next( 2 ) );
            do
            {
                writeRecords( cursor );
            } while ( cursor.shouldRetry() );
            assertTrue( cursor.next( 0 ) );
            do
            {
                writeRecords( cursor );
            } while ( cursor.shouldRetry() );
            assertTrue( cursor.next( 1 ) );
            do
            {
                writeRecords( cursor );
            } while ( cursor.shouldRetry() );
        }

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            while ( cursor.next() )
            {
                verifyRecordsMatchExpected( cursor );
            }
        }

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() )
            {
                verifyRecordsMatchExpected( cursor );
            }
        }
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writesOfDifferentUnitsMustHaveCorrectEndianess() throws Exception
    {
        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), 20 );

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            byte[] data = { 42, 43, 44, 45, 46 };

            cursor.putLong( 41 );          //  0+8 = 8
            cursor.putInt( 41 );           //  8+4 = 12
            cursor.putShort( (short) 41 ); // 12+2 = 14
            cursor.putByte( (byte) 41 );   // 14+1 = 15
            cursor.putBytes( data );       // 15+5 = 20
        }

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );

            long a = cursor.getLong();  //  8
            int b = cursor.getInt();    // 12
            short c = cursor.getShort();// 14
            byte[] data = new byte[] {
                    cursor.getByte(),   // 15
                    cursor.getByte(),   // 16
                    cursor.getByte(),   // 17
                    cursor.getByte(),   // 18
                    cursor.getByte(),   // 19
                    cursor.getByte()    // 20
            };
            cursor.setOffset( 0 );
            cursor.putLong( 1 + a );
            cursor.putInt( 1 + b );
            cursor.putShort( (short) (1 + c) );
            for ( byte d : data )
            {
                d++;
                cursor.putByte( d );
            }
        }

        pagedFile.close();

        StoreChannel channel = fs.open( file( "a" ), "r" );
        ByteBuffer buf = ByteBuffer.allocate( 20 );
        channel.read( buf );
        buf.flip();

        assertThat( buf.getLong(), is( 42L ) );
        assertThat( buf.getInt(), is( 42 ) );
        assertThat( buf.getShort(), is( (short) 42 ) );
        assertThat( buf.get(), is( (byte) 42 ) );
        assertThat( buf.get(), is( (byte) 43 ) );
        assertThat( buf.get(), is( (byte) 44 ) );
        assertThat( buf.get(), is( (byte) 45 ) );
        assertThat( buf.get(), is( (byte) 46 ) );
        assertThat( buf.get(), is( (byte) 47 ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileSecondTimeWithLesserPageSizeMustThrow() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile ignore = pageCache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pageCache.map( file( "a" ), filePageSize - 1 );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mappingFileSecondTimeWithGreaterPageSizeMustThrow() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile ignore = pageCache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalArgumentException.class );
            pageCache.map( file( "a" ), filePageSize + 1 );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void allowOpeningMultipleReadAndWriteCursorsPerThread() throws Exception
    {
        configureStandardPageCache();

        File fileA = existingFile( "a" );
        File fileB = existingFile( "b" );

        generateFileWithRecords( fileA, 1, 16 );
        generateFileWithRecords( fileB, 1, 16 );

        try ( PagedFile pfA = pageCache.map( fileA, filePageSize );
              PagedFile pfB = pageCache.map( fileB, filePageSize );
              PageCursor a = pfA.io( 0, PF_SHARED_READ_LOCK );
              PageCursor b = pfA.io( 0, PF_SHARED_READ_LOCK );
              PageCursor c = pfA.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor d = pfA.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor e = pfB.io( 0, PF_SHARED_READ_LOCK );
              PageCursor f = pfB.io( 0, PF_SHARED_READ_LOCK );
              PageCursor g = pfB.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor h = pfB.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( a.next() );
            assertTrue( b.next() );
            assertTrue( c.next() );
            assertTrue( d.next() );
            assertTrue( e.next() );
            assertTrue( f.next() );
            assertTrue( g.next() );
            assertTrue( h.next() );
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustNotLiveLockIfWeRunOutOfEvictablePages() throws Exception
    {
        configureStandardPageCache();

        List<PageCursor> cursors = new LinkedList<>();
        try ( PagedFile pf = pageCache.map( existingFile( "a" ), filePageSize ) )
        {
            try
            {
                expectedException.expect( IOException.class );
                //noinspection InfiniteLoopStatement
                for ( long i = 0;; i++ )
                {
                    PageCursor cursor = pf.io( i, PF_SHARED_WRITE_LOCK );
                    cursors.add( cursor );
                    assertTrue( cursor.next() );
                }
            }
            finally
            {
                cursors.forEach( PageCursor::close );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeLocksMustNotBeExclusive() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( existingFile( "a" ), filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );

            executor.submit( () -> {
                try ( PageCursor innerCursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
                {
                    assertTrue( innerCursor.next() );
                }
                return null;
            }).get();
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeLockMustInvalidateInnerReadLock() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( existingFile( "a" ), filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );

            executor.submit( () -> {
                try ( PageCursor innerCursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
                {
                    assertTrue( innerCursor.next() );
                    assertTrue( innerCursor.shouldRetry() );
                }
                return null;
            }).get();
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeLockMustInvalidateExistingReadLock() throws Exception
    {
        configureStandardPageCache();

        BinaryLatch startLatch = new BinaryLatch();
        BinaryLatch continueLatch = new BinaryLatch();

        try ( PagedFile pf = pageCache.map( existingFile( "a" ), filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() ); // Ensure that page 0 exists so the read cursor can get it
            assertTrue( cursor.next() ); // Then unlock it

            Future<Object> read = executor.submit( () -> {
                try ( PageCursor innerCursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
                {
                    assertTrue( innerCursor.next() );
                    assertFalse( innerCursor.shouldRetry() );
                    startLatch.release();
                    continueLatch.await();
                    assertTrue( innerCursor.shouldRetry() );
                }
                return null;
            } );

            startLatch.await();
            assertTrue( cursor.next( 0 ) ); // Re-take the write lock on page 0.
            continueLatch.release();
            read.get();
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeUnlockMustInvalidateReadLocks() throws Exception
    {
        configureStandardPageCache();

        BinaryLatch startLatch = new BinaryLatch();
        BinaryLatch continueLatch = new BinaryLatch();

        try ( PagedFile pf = pageCache.map( existingFile( "a" ), filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() ); // Lock page 0

            Future<Object> read = executor.submit( () -> {
                try ( PageCursor innerCursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
                {
                    assertTrue( innerCursor.next() );
                    assertTrue( innerCursor.shouldRetry() );
                    startLatch.release();
                    continueLatch.await();
                    assertTrue( innerCursor.shouldRetry() );
                }
                return null;
            } );

            startLatch.await();
            assertTrue( cursor.next() ); // Unlock page 0
            continueLatch.release();
            read.get();
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS)
    public void mustNotFlushCleanPagesWhenEvicting() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        final AtomicBoolean observedWrite = new AtomicBoolean();
        FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs ) {
            @Override
            public StoreChannel open( File fileName, String mode ) throws IOException
            {
                StoreChannel channel = super.open( fileName, mode );
                return new DelegatingStoreChannel( channel ) {
                    @Override
                    public int write( ByteBuffer src, long position ) throws IOException
                    {
                        observedWrite.set( true );
                        throw new IOException( "not allowed" );
                    }

                    @Override
                    public long write( ByteBuffer[] srcs, int offset, int length ) throws IOException
                    {
                        observedWrite.set( true );
                        throw new IOException( "not allowed" );
                    }

                    @Override
                    public void writeAll( ByteBuffer src, long position ) throws IOException
                    {
                        observedWrite.set( true );
                        throw new IOException( "not allowed" );
                    }

                    @Override
                    public void writeAll( ByteBuffer src ) throws IOException
                    {
                        observedWrite.set( true );
                        throw new IOException( "not allowed" );
                    }

                    @Override
                    public int write( ByteBuffer src ) throws IOException
                    {
                        observedWrite.set( true );
                        throw new IOException( "not allowed" );
                    }

                    @Override
                    public long write( ByteBuffer[] srcs ) throws IOException
                    {
                        observedWrite.set( true );
                        throw new IOException( "not allowed" );
                    }
                };
            }
        };
        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );

        try( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
             PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() )
            {
                verifyRecordsMatchExpected( cursor );
            }
        }
        assertFalse( observedWrite.get() );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void evictionMustFlushPagesToTheRightFiles() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        int filePageSize2 = filePageSize - 3; // diff. page size just to be difficult
        long maxPageIdCursor1 = recordCount / recordsPerFilePage;
        File file2 = file( "b" );
        OutputStream outputStream = fs.openAsOutputStream( file2, false );
        long file2sizeBytes = (maxPageIdCursor1 + 17) * filePageSize2;
        for ( int i = 0; i < file2sizeBytes; i++ )
        {
            // We will ues the page cache to change these 'a's into 'b's.
            outputStream.write( 'a' );
        }
        outputStream.flush();
        outputStream.close();

        configureStandardPageCache();

        PagedFile pagedFile1 = pageCache.map( file( "a" ), filePageSize );
        PagedFile pagedFile2 = pageCache.map( file2, filePageSize2 );

        long pageId1 = 0;
        long pageId2 = 0;
        boolean moreWorkToDo;
        do {
            boolean cursorReady1;
            boolean cursorReady2;

            try ( PageCursor cursor = pagedFile1.io( pageId1, PF_SHARED_WRITE_LOCK ) )
            {
                cursorReady1 = cursor.next() && cursor.getCurrentPageId() < maxPageIdCursor1;
                if ( cursorReady1 )
                {
                    writeRecords( cursor );
                    pageId1++;
                }
            }

            try ( PageCursor cursor = pagedFile2.io( pageId2, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
            {
                cursorReady2 = cursor.next();
                if ( cursorReady2 )
                {
                    do {
                        for ( int i = 0; i < filePageSize2; i++ )
                        {
                            cursor.putByte( (byte) 'b' );
                        }
                    }
                    while ( cursor.shouldRetry() );
                }
                pageId2++;
            }

            moreWorkToDo = cursorReady1 || cursorReady2;
        }
        while ( moreWorkToDo );

        pagedFile1.close();
        pagedFile2.close();

        // Verify the file contents
        assertThat( fs.getFileSize( file2 ), is( file2sizeBytes ) );
        InputStream inputStream = fs.openAsInputStream( file2 );
        for ( int i = 0; i < file2sizeBytes; i++ )
        {
            int b = inputStream.read();
            assertThat( b, is( (int) 'b' ) );
        }
        assertThat( inputStream.read(), is( -1 ) );
        inputStream.close();

        StoreChannel channel = fs.open( file( "a" ), "r" );
        ByteBuffer bufB = ByteBuffer.allocate( recordSize );
        for ( int i = 0; i < recordCount; i++ )
        {
            bufA.clear();
            channel.read( bufA );
            bufA.flip();
            bufB.clear();
            generateRecordForId( i, bufB );
            assertThat( bufB.array(), byteArray( bufA.array() ) );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void tracerMustBeNotifiedAboutPinUnpinFaultAndEvictEventsWhenReading() throws IOException
    {
        DefaultPageCacheTracer tracer = new DefaultPageCacheTracer();
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        getPageCache( fs, maxPages, pageCachePageSize, tracer );

        long countedPages = 0;
        long countedFaults = 0;
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            while ( cursor.next() )
            {
                countedPages++;
                countedFaults++;
            }

            // Using next( pageId ) to the already-pinned page id does not count,
            // so we only increment once for this section
            countedPages++;
            for ( int i = 0; i < 20; i++ )
            {
                assertTrue( cursor.next( 1 ) );
            }

            // But if we use next( pageId ) to a page that is different from the one already pinned,
            // then it counts
            for ( int i = 0; i < 20; i++ )
            {
                assertTrue( cursor.next( i ) );
                countedPages++;
            }
        }

        assertThat( "wrong count of pins", tracer.pins(), is( countedPages ) );
        assertThat( "wrong count of unpins", tracer.unpins(), is( countedPages ) );

        // We might be unlucky and fault in the second next call, on the page
        // we brought up in the first next call. That's why we assert that we
        // have observed *at least* the countedPages number of faults.
        long faults = tracer.faults();
        long bytesRead = tracer.bytesRead();
        assertThat( "wrong count of faults", faults, greaterThanOrEqualTo( countedFaults ) );
        assertThat( "wrong number of bytes read",
                bytesRead, greaterThanOrEqualTo( countedFaults * filePageSize ) );
        // Every page we move forward can put the freelist behind so the cache
        // wants to evict more pages. Plus, every page fault we do could also
        // block and get a page directly transferred to it, and these kinds of
        // evictions can count in addition to the evictions we do when the
        // cache is behind on keeping the freelist full.
        assertThat( "wrong count of evictions", tracer.evictions(),
                both( greaterThanOrEqualTo( countedFaults - maxPages ) )
                        .and( lessThanOrEqualTo( countedPages + faults ) ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void tracerMustBeNotifiedAboutPinUnpinFaultFlushAndEvictionEventsWhenWriting() throws IOException
    {
        long pagesToGenerate = 142;
        DefaultPageCacheTracer tracer = new DefaultPageCacheTracer();

        getPageCache( fs, maxPages, pageCachePageSize, tracer );

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( long i = 0; i < pagesToGenerate; i++ )
            {
                assertTrue( cursor.next() );
                assertThat( cursor.getCurrentPageId(), is( i ) );
                assertTrue( cursor.next( i ) ); // This does not count as a pin
                assertThat( cursor.getCurrentPageId(), is( i ) );

                writeRecords( cursor );
            }

            // This counts as a single pin
            assertTrue( cursor.next( 0 ) );
            assertTrue( cursor.next( 0 ) );
        }

        assertThat( "wrong count of pins", tracer.pins(), is( pagesToGenerate + 1 ) );
        assertThat( "wrong count of unpins", tracer.unpins(), is( pagesToGenerate + 1 ) );

        // We might be unlucky and fault in the second next call, on the page
        // we brought up in the first next call. That's why we assert that we
        // have observed *at least* the countedPages number of faults.
        long faults = tracer.faults();
        assertThat( "wrong count of faults", faults, greaterThanOrEqualTo( pagesToGenerate ) );
        // Every page we move forward can put the freelist behind so the cache
        // wants to evict more pages. Plus, every page fault we do could also
        // block and get a page directly transferred to it, and these kinds of
        // evictions can count in addition to the evictions we do when the
        // cache is behind on keeping the freelist full.
        assertThat( "wrong count of evictions", tracer.evictions(),
                both( greaterThanOrEqualTo( pagesToGenerate - maxPages ) )
                        .and( lessThanOrEqualTo( pagesToGenerate + faults ) ) );

        // We use greaterThanOrEqualTo because we visit each page twice, and
        // that leaves a small window wherein we can race with eviction, have
        // the evictor flush the page, and then fault it back and mark it as
        // dirty again.
        // We also subtract 'maxPages' from the expected flush count, because
        // vectored IO may coalesce all the flushes we do as part of unmapping
        // the file, into a single flush.
        long flushes = tracer.flushes();
        long bytesWritten = tracer.bytesWritten();
        assertThat( "wrong count of flushes",
                flushes, greaterThanOrEqualTo( pagesToGenerate - maxPages ) );
        assertThat( "wrong count of bytes written",
                bytesWritten, greaterThanOrEqualTo( pagesToGenerate * filePageSize ) );
    }

    @Test
    public void tracerMustBeNotifiedOfReadAndWritePins() throws Exception
    {
        final AtomicInteger writeCount = new AtomicInteger();
        final AtomicInteger readCount = new AtomicInteger();

        DefaultPageCacheTracer tracer = new DefaultPageCacheTracer()
        {
            @Override
            public PinEvent beginPin( boolean writeLock, long filePageId, PageSwapper swapper )
            {
                (writeLock? writeCount : readCount).getAndIncrement();
                return super.beginPin( writeLock, filePageId, swapper );
            }
        };
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        getPageCache( fs, maxPages, pageCachePageSize, tracer );

        int pinsForRead = 13;
        int pinsForWrite = 42;

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
            {
                for ( int i = 0; i < pinsForRead; i++ )
                {
                    assertTrue( cursor.next() );
                }
            }

            dirtyManyPages( pagedFile, pinsForWrite );
        }

        assertThat( "wrong read pin count", readCount.get(), is( pinsForRead ) );
        assertThat( "wrong write pin count", writeCount.get(), is( pinsForWrite ) );
    }

    @Test
    public void lastPageIdOfEmptyFileIsLessThanZero() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            assertThat( pagedFile.getLastPageId(), lessThan( 0L ) );
        }
    }

    @Test
    public void lastPageIdOfFileWithOneByteIsZero() throws IOException
    {
        StoreChannel channel = fs.create( file( "a" ) );
        channel.write( ByteBuffer.wrap( new byte[]{1} ) );
        channel.close();

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            assertThat( pagedFile.getLastPageId(), is( 0L ) );
        }
    }

    @Test
    public void lastPageIdOfFileWithExactlyTwoPagesWorthOfDataIsOne() throws IOException
    {
        int twoPagesWorthOfRecords = recordsPerFilePage * 2;
        generateFileWithRecords( file( "a" ), twoPagesWorthOfRecords, recordSize );

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            assertThat( pagedFile.getLastPageId(), is( 1L ) );
        }
    }

    @Test
    public void lastPageIdOfFileWithExactlyTwoPagesAndOneByteWorthOfDataIsTwo() throws IOException
    {
        int twoPagesWorthOfRecords = recordsPerFilePage * 2;
        generateFileWithRecords( file( "a" ), twoPagesWorthOfRecords, recordSize );
        OutputStream outputStream = fs.openAsOutputStream( file( "a" ), true );
        outputStream.write( 'a' );
        outputStream.close();

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            assertThat( pagedFile.getLastPageId(), is( 2L ) );
        }
    }

    @Test
    public void lastPageIdMustNotIncreaseWhenReadingToEndWithReadLock() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );
        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        long initialLastPageId = pagedFile.getLastPageId();
        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            //noinspection StatementWithEmptyBody
            while ( cursor.next() )
            {
                // scan through the lot
            }
        }
        long resultingLastPageId = pagedFile.getLastPageId();
        pagedFile.close();
        assertThat( resultingLastPageId, is( initialLastPageId ) );
    }

    @Test
    public void lastPageIdMustNotIncreaseWhenReadingToEndWithNoGrowAndWriteLock() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );
        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        long initialLastPageId = pagedFile.getLastPageId();
        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            //noinspection StatementWithEmptyBody
            while ( cursor.next() )
            {
                // scan through the lot
            }
        }
        long resultingLastPageId = pagedFile.getLastPageId();

        try
        {
            assertThat( resultingLastPageId, is( initialLastPageId ) );
        }
        finally
        {
            //noinspection ThrowFromFinallyBlock
            pagedFile.close();
        }
    }

    @Test
    public void lastPageIdMustIncreaseWhenScanningPastEndWithWriteLock()
            throws IOException
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 10, recordSize );
        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        assertThat( pagedFile.getLastPageId(), is( 9L ) );
        dirtyManyPages( pagedFile, 15 );
        try
        {
            assertThat( pagedFile.getLastPageId(), is( 14L ) );
        }
        finally
        {
            //noinspection ThrowFromFinallyBlock
            pagedFile.close();
        }
    }

    @Test
    public void lastPageIdMustIncreaseWhenJumpingPastEndWithWriteLock()
            throws IOException
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 10, recordSize );
        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        assertThat( pagedFile.getLastPageId(), is( 9L ) );
        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next( 15 ) );
        }
        try
        {
            assertThat( pagedFile.getLastPageId(), is( 15L ) );
        }
        finally
        {
            //noinspection ThrowFromFinallyBlock
            pagedFile.close();
        }
    }

    @Test
    public void lastPageIdFromUnmappedFileMustThrow() throws IOException
    {
        configureStandardPageCache();

        PagedFile file;
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize, StandardOpenOption.CREATE ) )
        {
            file = pf;
        }

        expectedException.expect( IllegalStateException.class );
        file.getLastPageId();
    }

    @Test
    public void cursorOffsetMustBeUpdatedReadAndWrite() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                verifyWriteOffsets( cursor );

                cursor.setOffset( 0 );
                verifyReadOffsets( cursor );
            }

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                verifyReadOffsets( cursor );
            }
        }
    }

    private void verifyWriteOffsets( PageCursor cursor )
    {
        assertThat( cursor.getOffset(), is( 0 ) );
        cursor.putLong( 1 );
        assertThat( cursor.getOffset(), is( 8 ) );
        cursor.putInt( 1 );
        assertThat( cursor.getOffset(), is( 12 ) );
        cursor.putShort( (short) 1 );
        assertThat( cursor.getOffset(), is( 14 ) );
        cursor.putByte( (byte) 1 );
        assertThat( cursor.getOffset(), is( 15 ) );
        cursor.putBytes( new byte[]{1, 2, 3} );
        assertThat( cursor.getOffset(), is( 18 ) );
        cursor.putBytes( new byte[]{1, 2, 3}, 1, 1 );
        assertThat( cursor.getOffset(), is( 19 ) );
    }

    private void verifyReadOffsets( PageCursor cursor )
    {
        assertThat( cursor.getOffset(), is( 0 ) );
        cursor.getLong();
        assertThat( cursor.getOffset(), is( 8 ) );
        cursor.getInt();
        assertThat( cursor.getOffset(), is( 12 ) );
        cursor.getShort();
        assertThat( cursor.getOffset(), is( 14 ) );
        cursor.getByte();
        assertThat( cursor.getOffset(), is( 15 ) );
        cursor.getBytes( new byte[3] );
        assertThat( cursor.getOffset(), is( 18 ) );
        cursor.getBytes( new byte[3], 1, 1 );
        assertThat( cursor.getOffset(), is( 19 ) );

        byte[] expectedBytes = new byte[] {
                0, 0, 0, 0, 0, 0, 0, 1, // first; long
                0, 0, 0, 1, // second; int
                0, 1, // third; short
                1, // fourth; byte
                1, 2, 3, // lastly; more bytes
                2
        };
        byte[] actualBytes = new byte[19];
        cursor.setOffset( 0 );
        cursor.getBytes( actualBytes );
        assertThat( actualBytes, byteArray( expectedBytes ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void closeOnPageCacheMustThrowIfFilesAreStillMapped() throws IOException
    {
        configureStandardPageCache();

        try ( PagedFile ignore = pageCache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( IllegalStateException.class );
            pageCache.close();
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void pagedFileIoMustThrowIfFileIsUnmapped() throws IOException
    {
        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        closeThisPagedFile( pagedFile );

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            try
            {
                cursor.next();
                fail( "cursor.next() on unmapped file did not throw" );
            }
            catch ( IllegalStateException e )
            {
                StringWriter out = new StringWriter();
                e.printStackTrace( new PrintWriter( out ) );
                assertThat( out.toString(), containsString( "closeThisPagedFile" ) );
            }
        }
    }

    protected void closeThisPagedFile( PagedFile pagedFile ) throws IOException
    {
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeLockedPageCursorNextMustThrowIfFileIsUnmapped() throws IOException
    {
        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK );
        closeThisPagedFile( pagedFile );

        try
        {
            cursor.next();
            fail( "cursor.next() on unmapped file did not throw" );
        }
        catch ( IllegalStateException e )
        {
            StringWriter out = new StringWriter();
            e.printStackTrace( new PrintWriter( out ) );
            assertThat( out.toString(), containsString( "closeThisPagedFile" ) );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeLockedPageCursorNextWithIdMustThrowIfFileIsUnmapped() throws IOException
    {
        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK );
        pagedFile.close();

        expectedException.expect( IllegalStateException.class );
        cursor.next( 1 );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readLockedPageCursorNextMustThrowIfFileIsUnmapped() throws IOException
    {
        generateFileWithRecords( file( "a" ), 1, recordSize );

        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK );
        pagedFile.close();

        expectedException.expect( IllegalStateException.class );
        cursor.next();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readLockedPageCursorNextWithIdMustThrowIfFileIsUnmapped() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );

        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK );
        pagedFile.close();

        expectedException.expect( IllegalStateException.class );
        cursor.next( 1 );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeLockedPageMustBlockFileUnmapping() throws Exception
    {
        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK );
        assertTrue( cursor.next() );

        Thread unmapper = fork( $close( pagedFile ) );
        unmapper.join( 100 );

        cursor.close();
        unmapper.join();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void optimisticReadLockedPageMustNotBlockFileUnmapping() throws Exception
    {
        generateFileWithRecords( file( "a" ), 1, recordSize );

        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK );
        assertTrue( cursor.next() ); // Got a read lock

        fork( $close( pagedFile ) ).join();

        cursor.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void advancingPessimisticReadLockingCursorAfterUnmappingMustThrow() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );

        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK );
        assertTrue( cursor.next() ); // Got a pessimistic read lock

        fork( $close( pagedFile ) ).join();

        expectedException.expect( IllegalStateException.class );
        cursor.next();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void advancingOptimisticReadLockingCursorAfterUnmappingMustThrow() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );

        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK );
        assertTrue( cursor.next() );    // fault
        assertTrue( cursor.next() );    // fault + unpin page 0
        assertTrue( cursor.next( 0 ) ); // potentially optimistic read lock page 0

        fork( $close( pagedFile ) ).join();

        try {
            cursor.next();
            fail( "Advancing the cursor should have thrown" );
        }
        catch ( IllegalStateException e )
        {
            // Yay!
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readingAndRetryingOnPageWithOptimisticReadLockingAfterUnmappingMustNotThrow() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );

        configureStandardPageCache();

        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
        PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK );
        assertTrue( cursor.next() );    // fault
        assertTrue( cursor.next() );    // fault + unpin page 0
        assertTrue( cursor.next( 0 ) ); // potentially optimistic read lock page 0

        fork( $close( pagedFile ) ).join();
        pageCache.close();
        pageCache = null;

        cursor.getByte();
        cursor.shouldRetry();
        try {
            cursor.next();
            fail( "Advancing the cursor should have thrown" );
        }
        catch ( IllegalStateException e )
        {
            // Yay!
        }
    }

    @Test
    public void shouldRetryFromUnboundReadCursorMustNotThrow() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertFalse( cursor.shouldRetry() );
        }
    }

    @Test
    public void shouldRetryFromUnboundWriteCursorMustNotThrow() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertFalse( cursor.shouldRetry() );
        }
    }

    @Test
    public void shouldRetryFromUnboundLinkedReadCursorMustNotThrow() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage * 2, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( cursor.next() );
            //noinspection unused
            try ( PageCursor linked = cursor.openLinkedCursor( 1 ) )
            {
                assertFalse( cursor.shouldRetry() );
            }
        }
    }

    @Test
    public void shouldRetryFromUnboundLinkedWriteCursorMustNotThrow() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage * 2, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            //noinspection unused
            try ( PageCursor linked = cursor.openLinkedCursor( 1 ) )
            {
                assertFalse( cursor.shouldRetry() );
            }
        }
    }

    @Test
    public void pageCursorCloseShouldNotReturnAlreadyClosedLinkedCursorToPool() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage * 2, recordSize );
        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            PageCursor a = pf.io( 0, PF_SHARED_WRITE_LOCK );
            PageCursor b = a.openLinkedCursor( 0 );
            b.close();
            PageCursor c = a.openLinkedCursor( 0 ); // Will close b again, creating a loop in the CursorPool
            PageCursor d = pf.io( 0, PF_SHARED_WRITE_LOCK ); // Same object as c because of loop in pool
            assertNotSame( c, d );
            c.close();
            d.close();
        }
    }

    @Test
    public void pageCursorCloseShouldNotReturnSameObjectToCursorPoolTwice() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage * 2, recordSize );
        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            PageCursor a = pf.io( 0, PF_SHARED_WRITE_LOCK );
            a.close();
            a.close(); // Return same object to CursorPool again, creating a Loop
            PageCursor b = pf.io( 0, PF_SHARED_WRITE_LOCK );
            PageCursor c = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertNotSame( b, c );
            b.close();
            c.close();
        }
    }

    @Test
    public void pageCursorCloseWithClosedLinkedCursorShouldNotReturnSameObjectToCursorPoolTwice() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage * 2, recordSize );
        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            PageCursor a = pf.io( 0, PF_SHARED_WRITE_LOCK );
            a.openLinkedCursor( 0 );
            a.openLinkedCursor( 0 ).close();
            a.close();

            PageCursor x = pf.io( 0, PF_SHARED_WRITE_LOCK );
            PageCursor y = pf.io( 0, PF_SHARED_WRITE_LOCK );
            PageCursor z = pf.io( 0, PF_SHARED_WRITE_LOCK );

            assertNotSame( x, y );
            assertNotSame( x, z );
            assertNotSame( y, z );
            x.close();
            y.close();
            z.close();
        }
    }

    @Test
    public void pageCursorCloseMustNotClosePreviouslyLinkedCursorThatGotReused() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage * 2, recordSize );
        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            PageCursor a = pf.io( 0, PF_SHARED_WRITE_LOCK );
            a.openLinkedCursor( 0 ).close();
            PageCursor x = pf.io( 0, PF_SHARED_WRITE_LOCK );
            a.close();
            assertTrue( x.next( 1 ) );
            x.close();
        }
    }

    private interface PageCursorAction
    {
        void apply( PageCursor cursor );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void getByteBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( PageCursor::getByte );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void putByteBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( cursor -> cursor.putByte( (byte) 42 ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void getShortBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( PageCursor::getShort );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void putShortBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( cursor -> cursor.putShort( (short) 42 ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void getIntBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( PageCursor::getInt );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void putIntBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( cursor -> cursor.putInt( 42 ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void putLongBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( cursor -> cursor.putLong( 42 ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void getLongBeyondPageEndMustThrow() throws IOException
    {
        verifyPageBounds( PageCursor::getLong );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void putBytesBeyondPageEndMustThrow() throws IOException
    {
        final byte[] bytes = new byte[] { 1, 2, 3 };
        verifyPageBounds( cursor -> cursor.putBytes( bytes ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void getBytesBeyondPageEndMustThrow() throws IOException
    {
        final byte[] bytes = new byte[3];
        verifyPageBounds( cursor -> cursor.getBytes( bytes ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void putBytesWithOffsetAndLengthBeyondPageEndMustThrow() throws IOException
    {
        final byte[] bytes = new byte[] { 1, 2, 3 };
        verifyPageBounds( cursor -> cursor.putBytes( bytes, 1, 1 ) );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void getBytesWithOffsetAndLengthBeyondPageEndMustThrow() throws IOException
    {
        final byte[] bytes = new byte[3];
        verifyPageBounds( cursor -> cursor.getBytes( bytes, 1, 1 ) );
    }

    private void verifyPageBounds( PageCursorAction action ) throws IOException
    {
        generateFileWithRecords( file( "a" ), 1, recordSize );

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            cursor.next();
            expectedException.expect( IndexOutOfBoundsException.class );
            for ( int i = 0; i < 100000; i++ )
            {
                action.apply( cursor );
                if ( cursor.checkAndClearBoundsFlag() )
                {
                    throw new IndexOutOfBoundsException();
                }
            }
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void shouldRetryMustClearBoundsFlagWhenReturningTrue() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );

            assertTrue( reader.next() );
            reader.getByte( -1 ); // out-of-bounds flag now raised
            writer.close(); // reader overlapped with writer, so must retry
            assertTrue( reader.shouldRetry() );

            // shouldRetry returned 'true', so it must clear the out-of-bounds flag
            assertFalse( reader.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void shouldRetryMustNotClearBoundsFlagWhenReturningFalse() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );
            writer.close(); // writer closed before reader comes to this page, so no need for retry

            assertTrue( reader.next() );
            reader.getByte( -1 ); // out-of-bounds flag now raised
            assertFalse( reader.shouldRetry() );

            // shouldRetry returned 'true', so it must clear the out-of-bounds flag
            assertTrue( reader.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void nextThatReturnsTrueMustNotClearBoundsFlagOnReadCursor() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );

            assertTrue( reader.next() );
            reader.getByte( -1 ); // out-of-bounds flag now raised
            writer.next(); // make sure there's a next page for the reader to move to
            writer.close(); // reader overlapped with writer, so must retry
            assertTrue( reader.next() );

            assertTrue( reader.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void nextThatReturnsTrueMustNotClearBoundsFlagOnWriteCursor() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( writer.next() );
            writer.getByte( -1 ); // out-of-bounds flag now raised
            assertTrue( writer.next() );

            assertTrue( writer.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void nextThatReturnsFalseMustNotClearBoundsFlagOnReadCursor() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );

            assertTrue( reader.next() );
            reader.getByte( -1 ); // out-of-bounds flag now raised
            // don't call next of the writer, so there won't be a page for the reader to move onto
            writer.close(); // reader overlapped with writer, so must retry
            assertFalse( reader.next() );

            assertTrue( reader.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void nextThatReturnsFalseMustNotClearBoundsFlagOnWriteCursor() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordsPerFilePage, recordSize );
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            assertTrue( writer.next() );
            writer.getByte( -1 ); // out-of-bounds flag now raised
            assertFalse( writer.next() );

            assertTrue( writer.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void nextWithPageIdThatReturnsTrueMustNotClearBoundsFlagOnReadCursor() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );

            assertTrue( reader.next() );
            reader.getByte( -1 ); // out-of-bounds flag now raised
            writer.next( 3 ); // make sure there's a next page for the reader to move to
            writer.close(); // reader overlapped with writer, so must retry
            assertTrue( reader.next( 3 ) );

            assertTrue( reader.checkAndClearBoundsFlag() );
        }
    }

    @Test(timeout = SHORT_TIMEOUT_MILLIS)
    public void nextWithPageIdMustNotClearBoundsFlagOnWriteCursor() throws Exception
    {
        configureStandardPageCache();

        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( writer.next() );
            writer.getByte( -1 ); // out-of-bounds flag now raised
            assertTrue( writer.next( 3 ) );

            assertTrue( writer.checkAndClearBoundsFlag() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void settingOutOfBoundsCursorOffsetMustNotRaiseBoundsFlag() throws IOException
    {
        generateFileWithRecords( file( "a" ), 1, recordSize );

        configureStandardPageCache();
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            cursor.setOffset( -1 );
            assertFalse( cursor.checkAndClearBoundsFlag() );

            cursor.setOffset( filePageSize + 1 );
            assertFalse( cursor.checkAndClearBoundsFlag() );

            cursor.setOffset( pageCachePageSize + 1 );
            assertFalse( cursor.checkAndClearBoundsFlag() );
        }
    }

    @Test
    public void manuallyRaisedBoundsFlagMustBeObservable() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pagedFile.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() );
            writer.raiseOutOfBounds();
            assertTrue( writer.checkAndClearBoundsFlag() );

            assertTrue( reader.next() );
            reader.raiseOutOfBounds();
            assertTrue( reader.checkAndClearBoundsFlag() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void pageFaultForWriteMustThrowIfOutOfStorageSpace() throws IOException
    {
        final AtomicInteger writeCounter = new AtomicInteger();
        FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs )
        {
            @Override
            public StoreChannel open( File fileName, String mode ) throws IOException
            {
                return new DelegatingStoreChannel( super.open( fileName, mode ) )
                {
                    @Override
                    public void writeAll( ByteBuffer src, long position ) throws IOException
                    {
                        if ( writeCounter.incrementAndGet() > 10 )
                        {
                            throw new IOException( "No space left on device" );
                        }
                        super.writeAll( src, position );
                    }
                };
            }
        };

        fs.create( file( "a" ) ).close();

        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            expectedException.expect( IOException.class );
            //noinspection StatementWithEmptyBody
            while ( cursor.next() )
            {
                // Profound and interesting I/O.
            }
        }
        finally
        {
            // Unmapping and closing the PageCache will want to flush,
            // but we can't do that with a full drive.
            pageCache = null;
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void pageFaultForReadMustThrowIfOutOfStorageSpace() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        final AtomicInteger writeCounter = new AtomicInteger();
        FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs )
        {
            @Override
            public StoreChannel open( File fileName, String mode ) throws IOException
            {
                return new DelegatingStoreChannel( super.open( fileName, mode ) )
                {
                    @Override
                    public void writeAll( ByteBuffer src, long position ) throws IOException
                    {
                        if ( writeCounter.incrementAndGet() >= 1 )
                        {
                            throw new IOException( "No space left on device" );
                        }
                        super.writeAll( src, position );
                    }
                };
            }
        };

        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        // Create 1 dirty page
        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
        }

        // Read pages until the dirty page gets flushed
        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            //noinspection InfiniteLoopStatement
            for (;;)
            {
                expectedException.expect( IOException.class );
                //noinspection StatementWithEmptyBody
                while ( cursor.next() )
                {
                    // Profound and interesting I/O.
                }
                // Use rewind if we get to the end, because it is non-
                // deterministic which pages get evicted and when.
                cursor.rewind();
            }
        }
        finally
        {
            // Unmapping and closing the PageCache will want to flush,
            // but we can't do that with a full drive.
            pageCache = null;
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void mustRecoverFromFullDriveWhenMoreStorageBecomesAvailable() throws IOException
    {
        final AtomicBoolean hasSpace = new AtomicBoolean();
        FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs )
        {
            @Override
            public StoreChannel open( File fileName, String mode ) throws IOException
            {
                return new DelegatingStoreChannel( super.open( fileName, mode ) )
                {
                    @Override
                    public void writeAll( ByteBuffer src, long position ) throws IOException
                    {
                        if ( !hasSpace.get() )
                        {
                            throw new IOException( "No space left on device" );
                        }
                        super.writeAll( src, position );
                    }
                };
            }
        };

        fs.create( file( "a" ) ).close();

        getPageCache( fs, maxPages, pageCachePageSize, PageCacheTracer.NULL );
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            //noinspection InfiniteLoopStatement
            for (;;) // Keep writing until we get an exception! (when the cache starts evicting stuff)
            {
                assertTrue( cursor.next() );
                writeRecords( cursor );
            }
        }
        catch ( IOException ignore )
        {
            // We're not out of space! Salty tears...
        }

        // Fix the situation:
        hasSpace.set( true );

        // Closing the last reference of a paged file implies a flush, and it mustn't throw:
        pagedFile.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void dataFromDifferentFilesMustNotBleedIntoEachOther() throws IOException
    {
        // The idea with this test is, that the pages for fileA are larger than
        // the pages for fileB, so we can put A-data beyond the end of the B
        // file pages.
        // Furthermore, our writes to the B-pages do not overwrite the entire page.
        // In those cases, the bytes not written to must be zeros.

        File fileB = existingFile( "b" );
        int filePageSizeA = pageCachePageSize - 2;
        int filePageSizeB = pageCachePageSize - 6;
        int pagesToWriteA = 100;
        int pagesToWriteB = 3;

        configureStandardPageCache();
        PagedFile pagedFileA = pageCache.map( existingFile( "a" ), filePageSizeA );

        try ( PageCursor cursor = pagedFileA.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < pagesToWriteA; i++ )
            {
                assertTrue( cursor.next() );
                for ( int j = 0; j < filePageSizeA; j++ )
                {
                    cursor.putByte( (byte) 42 );
                }
            }
        }

        PagedFile pagedFileB = pageCache.map( fileB, filePageSizeB );

        try ( PageCursor cursor = pagedFileB.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < pagesToWriteB; i++ )
            {
                assertTrue( cursor.next() );
                cursor.putByte( (byte) 63 );
            }
        }

        pagedFileA.close();
        pagedFileB.close();

        InputStream inputStream = fs.openAsInputStream( fileB );
        assertThat( "first page first byte", inputStream.read(), is( 63 ) );
        for ( int i = 0; i < filePageSizeB - 1; i++ )
        {
            assertThat( "page 0 byte pos " + i, inputStream.read(), is( 0 ) );
        }
        assertThat( "second page first byte", inputStream.read(), is( 63 ) );
        for ( int i = 0; i < filePageSizeB - 1; i++ )
        {
            assertThat( "page 1 byte pos " + i, inputStream.read(), is( 0 ) );
        }
        assertThat( "third page first byte", inputStream.read(), is( 63 ) );
        for ( int i = 0; i < filePageSizeB - 1; i++ )
        {
            assertThat( "page 2 byte pos " + i, inputStream.read(), is( 0 ) );
        }
        assertThat( "expect EOF", inputStream.read(), is( -1 ) );
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void freshlyCreatedPagesMustContainAllZeros() throws IOException
    {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( existingFile( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < 100; i++ )
            {
                assertTrue( cursor.next() );
                for ( int j = 0; j < filePageSize; j++ )
                {
                    cursor.putByte( (byte) rng.nextInt() );
                }
            }
        }
        pageCache.close();
        pageCache = null;
        System.gc(); // make sure underlying pages are finalizable
        System.gc(); // make sure underlying pages are finally collected

        configureStandardPageCache();

        try ( PagedFile pagedFile = pageCache.map( existingFile( "b" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < 100; i++ )
            {
                assertTrue( cursor.next() );
                for ( int j = 0; j < filePageSize; j++ )
                {
                    assertThat( cursor.getByte(), is( (byte) 0 ) );
                }
            }
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void optimisticReadLockMustFaultOnRetryIfPageHasBeenEvicted() throws Exception
    {
        final byte a = 'a';
        final byte b = 'b';
        final File fileA = existingFile( "a" );
        final File fileB = existingFile( "b" );

        configureStandardPageCache();

        final PagedFile pagedFileA = pageCache.map( fileA, filePageSize );
        final PagedFile pagedFileB = pageCache.map( fileB, filePageSize );

        // Fill fileA with some predicable data
        try ( PageCursor cursor = pagedFileA.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < maxPages; i++ )
            {
                assertTrue( cursor.next() );
                for ( int j = 0; j < filePageSize; j++ )
                {
                    cursor.putByte( a );
                }
            }
        }

        Runnable fillPagedFileB = () -> {
            try ( PageCursor cursor = pagedFileB.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                for ( int i = 0; i < maxPages * 30; i++ )
                {
                    assertTrue( cursor.next() );
                    for ( int j = 0; j < filePageSize; j++ )
                    {
                        cursor.putByte( b );
                    }
                }
            }
            catch ( IOException e )
            {
                e.printStackTrace();
            }
        };

        try ( PageCursor cursor = pagedFileA.io( 0, PF_SHARED_READ_LOCK ) )
        {
            // First, make sure page 0 is in the cache:
            assertTrue( cursor.next( 0 ) );
            // If we took a page fault, we'd have a pessimistic lock on page 0.
            // Move to the next page to release that lock:
            assertTrue( cursor.next() );
            // Now go back to page 0. It's still in the cache, so we should get
            // an optimistic lock, if that's available:
            assertTrue( cursor.next( 0 ) );

            // Verify the page is all 'a's:
            for ( int i = 0; i < filePageSize; i++ )
            {
                assertThat( cursor.getByte(), is( a ) );
            }

            // Now fill file B with 'b's... this will cause our current page to be evicted
            fork( fillPagedFileB ).join();
            // So if we had an optimistic lock, we should be asked to retry:
            if ( cursor.shouldRetry() )
            {
                // When we do reads after the shouldRetry() call, we should fault our page back
                // and get consistent reads (assuming we don't race any further with eviction)
                int expected = a * filePageSize;
                int actual;
                do
                {
                    actual = 0;
                    for ( int i = 0; i < filePageSize; i++ )
                    {
                        actual += cursor.getByte();
                    }
                }
                while ( cursor.shouldRetry() );
                assertThat( actual, is( expected ) );
            }
        }

        pagedFileA.close();
        pagedFileB.close();
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void pagesMustReturnToFreelistIfSwapInThrows() throws IOException
    {
        generateFileWithRecords( file( "a" ), recordCount, recordSize );

        configureStandardPageCache();
        PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );

        int iterations = maxPages * 2;
        accessPagesWhileInterrupted( pagedFile, PF_SHARED_READ_LOCK, iterations );
        accessPagesWhileInterrupted( pagedFile, PF_SHARED_WRITE_LOCK, iterations );

        // Verify that after all those troubles, page faulting starts working again
        // as soon as our thread is no longer interrupted and the PageSwapper no
        // longer throws.
        Thread.interrupted(); // make sure to clear our interruption status

        try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( cursor.next() );
            verifyRecordsMatchExpected( cursor );
        }
        pagedFile.close();
    }

    private void accessPagesWhileInterrupted(
            PagedFile pagedFile,
            int pf_flags,
            int iterations ) throws IOException
    {
        try ( PageCursor cursor = pagedFile.io( 0, pf_flags ) )
        {
            for ( int i = 0; i < iterations; i++ )
            {
                Thread.currentThread().interrupt();
                try
                {
                    cursor.next( 0 );
                }
                catch ( IOException ignored )
                {
                    // We don't care about the exception per se.
                    // We just want lots of failed page faults.
                }
            }
        }
    }

    // NOTE: This test is CPU architecture dependent, but it should fail on no
    // architecture that we support.
    // This test has no timeout because one may want to run it on a CPU
    // emulator, where it's not unthinkable for it to take minutes.
    @Test
    public void mustSupportUnalignedWordAccesses() throws Exception
    {
        // 8 MB pages, 10 of them for 80 MB.
        // This way we are sure to write across OS page boundaries. The default
        // size of Huge Pages on Linux is 2 MB, but it can be configured to be
        // as large as 1 GB - at least I have not heard of anyone trying to
        // configure it to be more than that.
        int pageSize = 1024 * 1024 * 8;
        getPageCache( fs, 10, pageSize, PageCacheTracer.NULL );

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );

            for ( int i = 0; i < pageSize - 8; i++ )
            {
                cursor.setOffset( i );
                long x = rng.nextLong();
                cursor.putLong( x );
                cursor.setOffset( i );
                String reason =
                        "Failed to read back the value that was written at " +
                        "offset " + toHexString( i );
                assertThat( reason,
                        toHexString( cursor.getLong() ),
                        is( toHexString( x ) ) );
            }
        }
    }

    @RepeatRule.Repeat( times = 50 )
    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustEvictPagesFromUnmappedFiles() throws Exception
    {
        // GIVEN mapping then unmapping
        configureStandardPageCache();
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
        }

        // WHEN using all pages, so that eviction of some pages will happen
        try ( PagedFile pagedFile = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < maxPages+5; i++ )
            {
                // THEN eviction happening here should not result in any exception
                assertTrue( cursor.next() );
            }
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustReadZerosFromBeyondEndOfFile() throws Exception
    {
        StandardRecordFormat recordFormat = new StandardRecordFormat();
        File[] files = {
                file( "1" ), file( "2" ), file( "3" ), file( "4" ), file( "5" ), file( "6" ),
                file( "7" ), file( "8" ), file( "9" ), file( "0" ), file( "A" ), file( "B" ),
        };
        for ( int fileId = 0; fileId < files.length; fileId++ )
        {
            File file = files[fileId];
            StoreChannel channel = fs.open( file, "rw" );
            for ( int recordId = 0; recordId < fileId + 1; recordId++ )
            {
                Record record = recordFormat.createRecord( file, recordId );
                recordFormat.writeRecord( record, channel );
            }
            channel.close();
        }

        int pageSize = nextPowerOf2( recordFormat.getRecordSize() * (files.length + 1) );
        getPageCache( fs, 2, pageSize, PageCacheTracer.NULL );

        int fileId = files.length;
        while ( fileId --> 0 )
        {
            File file = files[fileId];
            try ( PagedFile pf = pageCache.map( file, pageSize );
                  PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
            {
                int pageCount = 0;
                while( cursor.next() )
                {
                    pageCount++;
                    recordFormat.assertRecordsWrittenCorrectly( cursor );
                }
                assertThat( "pages in file " + file, pageCount, greaterThan( 0 ) );
            }
        }
    }

    private int nextPowerOf2( int i )
    {
        return 1 << (32 - Integer.numberOfLeadingZeros( i ) );
    }

    private PageSwapperFactory factoryCountingSyncDevice(
            final AtomicInteger syncDeviceCounter,
            final Queue<Integer> expectedCountsInForce )
    {
        SingleFilePageSwapperFactory factory = new SingleFilePageSwapperFactory()
        {
            @Override
            public void syncDevice()
            {
                super.syncDevice();
                syncDeviceCounter.getAndIncrement();
            }

            @Override
            public PageSwapper createPageSwapper(
                    File file, int filePageSize, PageEvictionCallback onEviction, boolean createIfNotExist ) throws IOException
            {
                PageSwapper delegate = super.createPageSwapper( file, filePageSize, onEviction, createIfNotExist );
                return new DelegatingPageSwapper( delegate )
                {
                    @Override
                    public void force() throws IOException
                    {
                        super.force();
                        assertThat( syncDeviceCounter.get(), is( expectedCountsInForce.poll() ) );
                    }
                };
            }
        };
        factory.setFileSystemAbstraction( fs );
        return factory;
    }

    @SafeVarargs
    private static <E> Queue<E> queue( E... items )
    {
        Queue<E> queue = new ConcurrentLinkedQueue<>();
        for ( E item : items )
        {
            queue.offer( item );
        }
        return queue;
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustSyncDeviceWhenFlushAndForcingPagedFile() throws Exception
    {
        AtomicInteger syncDeviceCounter = new AtomicInteger();
        AtomicInteger expectedCountInForce = new AtomicInteger();
        Queue<Integer> expectedCountsInForce = queue(
                0,      // at `p1.flushAndForce` no `syncDevice` has happened before the force
                1, 2 ); // closing+forcing the files one by one, we get 2 more `syncDevice`
        PageSwapperFactory factory = factoryCountingSyncDevice( syncDeviceCounter, expectedCountsInForce );
        try ( PageCache cache = createPageCache( factory, maxPages, pageCachePageSize, PageCacheTracer.NULL );
              PagedFile p1 = cache.map( existingFile( "a" ), filePageSize );
              PagedFile p2 = cache.map( existingFile( "b" ), filePageSize ) )
        {
            try ( PageCursor cursor = p1.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
            }
            try ( PageCursor cursor = p2.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
            }

            p1.flushAndForce();
            expectedCountInForce.set( 1 );
            assertThat( syncDeviceCounter.get(), is( 1 ) );
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustSyncDeviceWhenFlushAndForcingPageCache() throws Exception
    {
        AtomicInteger syncDeviceCounter = new AtomicInteger();
        AtomicInteger expectedCountInForce = new AtomicInteger();
        Queue<Integer> expectedCountsInForce = queue(
                0, 0,   // `cache.flushAndForce` forces the individual files, no `syncDevice` yet
                1, 2 ); // after test, files are closed+forced one by one
        PageSwapperFactory factory = factoryCountingSyncDevice( syncDeviceCounter, expectedCountsInForce );
        try ( PageCache cache = createPageCache( factory, maxPages, pageCachePageSize, PageCacheTracer.NULL );
              PagedFile p1 = cache.map( existingFile( "a" ), filePageSize );
              PagedFile p2 = cache.map( existingFile( "b" ), filePageSize ) )
        {
            try ( PageCursor cursor = p1.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
            }
            try ( PageCursor cursor = p2.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
            }

            cache.flushAndForce();
            expectedCountInForce.set( 1 );
            assertThat( syncDeviceCounter.get(), is( 1 ) );
        }
    }

    @Test(expected = NoSuchFileException.class)
    public void mustThrowWhenMappingNonExistingFile() throws Exception
    {
        configureStandardPageCache();
        pageCache.map( file( "does not exist" ), filePageSize );
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustCreateNonExistingFileWithCreateOption() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "does not exist" ), filePageSize, StandardOpenOption.CREATE );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ))
        {
            assertTrue( cursor.next() );
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustIgnoreCreateOptionIfFileAlreadyExists() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize, StandardOpenOption.CREATE );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ))
        {
            assertTrue( cursor.next() );
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustIgnoreCertainOpenOptions() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                StandardOpenOption.SPARSE );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ))
        {
            assertTrue( cursor.next() );
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mustThrowOnUnsupportedOpenOptions() throws Exception
    {
        configureStandardPageCache();
        verifyMappingWithOpenOptionThrows( StandardOpenOption.CREATE_NEW );
        verifyMappingWithOpenOptionThrows( StandardOpenOption.SYNC );
        verifyMappingWithOpenOptionThrows( StandardOpenOption.DSYNC );
        verifyMappingWithOpenOptionThrows( new OpenOption()
        {
            @Override
            public String toString()
            {
                return "NonStandardOpenOption";
            }
        } );
    }

    private void verifyMappingWithOpenOptionThrows( OpenOption option ) throws IOException
    {
        try
        {
            pageCache.map( file( "a" ), filePageSize, option ).close();
            fail( "Expected PageCache.map() to throw when given the OpenOption " + option );
        }
        catch ( IllegalArgumentException | UnsupportedOperationException e )
        {
            // good
        }
    }

    @Test( timeout = SEMI_LONG_TIMEOUT_MILLIS )
    public void mappingFileWithTruncateOptionMustTruncateFile() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pf.io( 10, PF_SHARED_WRITE_LOCK ) )
        {
            assertThat( pf.getLastPageId(), lessThan( 0L ) );
            assertTrue( cursor.next() );
            cursor.putInt( 0xcafebabe );
        }
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize, StandardOpenOption.TRUNCATE_EXISTING );
              PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertThat( pf.getLastPageId(), lessThan( 0L ) );
            assertFalse( cursor.next() );
        }
    }

    @SuppressWarnings( "unused" )
    @Test
    public void mappingAlreadyMappedFileWithTruncateOptionMustThrow() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile first = pageCache.map( file( "a" ), filePageSize ) )
        {
            expectedException.expect( UnsupportedOperationException.class );
            try ( PagedFile second = pageCache.map( file( "a" ), filePageSize, StandardOpenOption.TRUNCATE_EXISTING ) )
            {
                fail( "the second map call should have thrown" );
            }
        }
    }

    @Test
    public void mustThrowIfFileIsClosedMoreThanItIsMapped() throws Exception
    {
        configureStandardPageCache();
        PagedFile pf = pageCache.map( file( "a" ), filePageSize );
        pf.close();
        expectedException.expect( IllegalStateException.class );
        pf.close();
    }

    @Test
    public void fileMappedWithDeleteOnCloseMustNotExistAfterUnmap() throws Exception
    {
        configureStandardPageCache();
        pageCache.map( file( "a" ), filePageSize, StandardOpenOption.DELETE_ON_CLOSE ).close();
        expectedException.expect( NoSuchFileException.class );
        pageCache.map( file( "a" ), filePageSize );
    }

    @Test
    public void fileMappedWithDeleteOnCloseMustNotExistAfterLastUnmap() throws Exception
    {
        configureStandardPageCache();
        File file = file( "a" );
        try ( PagedFile ignore = pageCache.map( file, filePageSize ) )
        {
            pageCache.map( file, filePageSize, StandardOpenOption.DELETE_ON_CLOSE ).close();
        }
        expectedException.expect( NoSuchFileException.class );
        pageCache.map( file, filePageSize );
    }

    @Test
    public void mustNotThrowWhenMappingFileWithDifferentFilePageSizeAndAnyPageSizeIsSpecified() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile ignore = pageCache.map( file( "a" ), filePageSize ) )
        {
            pageCache.map( file( "a" ), filePageSize + 1, PageCacheOpenOptions.ANY_PAGE_SIZE ).close();
        }
    }

    @Test
    public void mustCopyIntoSameSizedWritePageCursor() throws Exception
    {
        configureStandardPageCache();
        int bytes = 200;

        // Put some data into the file
        try ( PagedFile pf = pageCache.map( file( "a" ), 32 );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            for ( int i = 0; i < bytes; i++ )
            {
                if ( (i & 31) == 0 )
                {
                    assertTrue( cursor.next() );
                }
                cursor.putByte( (byte) i );
            }
        }

        // Then copy all the pages into another file, with a larger file page size
        int pageSize = 16;
        try ( PagedFile pfA = pageCache.map( file( "a" ), pageSize );
              PagedFile pfB = pageCache.map( existingFile( "b" ), pageSize );
              PageCursor cursorA = pfA.io( 0, PF_SHARED_READ_LOCK );
              PageCursor cursorB = pfB.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            while ( cursorA.next() )
            {
                assertTrue( cursorB.next() );
                int bytesCopied;
                do
                {
                    bytesCopied = cursorA.copyTo( 0, cursorB, 0, cursorA.getCurrentPageSize() );
                }
                while ( cursorA.shouldRetry() );
                assertThat( bytesCopied, is( pageSize ) );
            }
        }

        // Finally, verify the contents of file 'b'
        try ( PagedFile pf = pageCache.map( file( "b" ), 32 );
              PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            for ( int i = 0; i < bytes; i++ )
            {
                if ( (i & 31) ==0 )
                {
                    assertTrue( cursor.next() );
                }
                int offset = cursor.getOffset();
                byte b;
                do
                {
                    cursor.setOffset( offset );
                    b = cursor.getByte();
                }
                while( cursor.shouldRetry() );
                assertThat( b, is( (byte) i ) );
            }
        }
    }

    @Test
    public void mustCopyIntoLargerPageCursor() throws Exception
    {
        configureStandardPageCache();
        int smallPageSize = 16;
        int largePageSize = 17;
        try ( PagedFile pfA = pageCache.map( file( "a" ), smallPageSize );
              PagedFile pfB = pageCache.map( existingFile( "b" ), largePageSize );
              PageCursor cursorA = pfA.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor cursorB = pfB.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursorA.next() );
            for ( int i = 0; i < smallPageSize; i++ )
            {
                cursorA.putByte( (byte) (i + 1) );
            }
            assertTrue( cursorB.next() );
            assertThat( cursorA.copyTo( 0, cursorB, 0, smallPageSize ), is( smallPageSize ) );
            for ( int i = 0; i < smallPageSize; i++ )
            {
                assertThat( cursorB.getByte(), is( (byte) (i + 1) ) );
            }
            assertThat( cursorB.getByte(), is( (byte) 0 ) );
        }
    }

    @Test
    public void mustCopyIntoSmallerPageCursor() throws Exception
    {
        configureStandardPageCache();
        int smallPageSize = 16;
        int largePageSize = 17;
        try ( PagedFile pfA = pageCache.map( file( "a" ), largePageSize );
              PagedFile pfB = pageCache.map( existingFile( "b" ), smallPageSize );
              PageCursor cursorA = pfA.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor cursorB = pfB.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursorA.next() );
            for ( int i = 0; i < largePageSize; i++ )
            {
                cursorA.putByte( (byte) (i + 1) );
            }
            assertTrue( cursorB.next() );
            assertThat( cursorA.copyTo( 0, cursorB, 0, largePageSize ), is( smallPageSize ) );
            for ( int i = 0; i < smallPageSize; i++ )
            {
                assertThat( cursorB.getByte(), is( (byte) (i + 1) ) );
            }
        }
    }

    @Test
    public void mustThrowOnCopyIntoReadPageCursor() throws Exception
    {
        configureStandardPageCache();
        int pageSize = 17;
        try ( PagedFile pfA = pageCache.map( file( "a" ), pageSize );
              PagedFile pfB = pageCache.map( existingFile( "b" ), pageSize ) )
        {
            // Create data
            try ( PageCursor cursorA = pfA.io( 0, PF_SHARED_WRITE_LOCK );
                  PageCursor cursorB = pfB.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursorA.next() );
                assertTrue( cursorB.next() );
            }

            // Try copying
            try ( PageCursor cursorA = pfA.io( 0, PF_SHARED_WRITE_LOCK );
                  PageCursor cursorB = pfB.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursorA.next() );
                assertTrue( cursorB.next() );
                expectedException.expect( IllegalArgumentException.class );
                cursorA.copyTo( 0, cursorB, 0, pageSize );
            }
        }
    }

    @Test
    public void copyToMustCheckBounds() throws Exception
    {
        configureStandardPageCache();
        int pageSize = 16;
        try ( PagedFile pf = pageCache.map( file( "a" ), pageSize );
              PageCursor cursorA = pf.io( 0, PF_SHARED_READ_LOCK );
              PageCursor cursorB = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursorB.next() );
            assertTrue( cursorB.next() );
            assertTrue( cursorA.next() );

            // source buffer underflow
            cursorA.copyTo( -1, cursorB, 0, 1 );
            assertTrue( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );

            // target buffer underflow
            cursorA.copyTo( 0, cursorB, -1, 1 );
            assertTrue( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );

            // source buffer offset overflow
            cursorA.copyTo( pageSize, cursorB, 0, 1 );
            assertTrue( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );

            // target buffer offset overflow
            cursorA.copyTo( 0, cursorB, pageSize, 1 );
            assertTrue( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );

            // source buffer length overflow
            assertThat( cursorA.copyTo( 1, cursorB, 0, pageSize ), is( pageSize - 1 ) );
            assertFalse( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );

            // target buffer length overflow
            assertThat( cursorA.copyTo( 0, cursorB, 1, pageSize ), is( pageSize - 1 ) );
            assertFalse( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );

            // negative length
            cursorA.copyTo( 1, cursorB, 1, -1 );
            assertTrue( cursorA.checkAndClearBoundsFlag() );
            assertFalse( cursorB.checkAndClearBoundsFlag() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readCursorsCanOpenLinkedCursor() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor parent = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor linked = parent.openLinkedCursor( 1 );
            assertTrue( parent.next() );
            assertTrue( linked.next() );
            verifyRecordsMatchExpected( parent );
            verifyRecordsMatchExpected( linked );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeCursorsCanOpenLinkedCursor() throws Exception
    {
        configureStandardPageCache();
        File file = file( "a" );
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor parent = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            PageCursor linked = parent.openLinkedCursor( 1 );
            assertTrue( parent.next() );
            assertTrue( linked.next() );
            writeRecords( parent );
            writeRecords( linked );
        }
        verifyRecordsInFile( file, recordsPerFilePage * 2 );
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void closingParentCursorMustCloseLinkedCursor() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            PageCursor writerParent = pf.io( 0, PF_SHARED_WRITE_LOCK );
            PageCursor readerParent = pf.io( 0, PF_SHARED_READ_LOCK );
            assertTrue( writerParent.next() );
            assertTrue( readerParent.next() );
            PageCursor writerLinked = writerParent.openLinkedCursor( 1 );
            PageCursor readerLinked = readerParent.openLinkedCursor( 1 );
            assertTrue( writerLinked.next() );
            assertTrue( readerLinked.next() );
            writerParent.close();
            readerParent.close();
            writerLinked.getByte( 0 );
            assertTrue( writerLinked.checkAndClearBoundsFlag() );
            readerLinked.getByte( 0 );
            assertTrue( readerLinked.checkAndClearBoundsFlag() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writeCursorWithNoGrowCanOpenLinkedCursorWithNoGrow() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor parent = pf.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            PageCursor linked = parent.openLinkedCursor( 1 );
            assertTrue( parent.next() );
            assertTrue( linked.next() );
            verifyRecordsMatchExpected( parent );
            verifyRecordsMatchExpected( linked );
            assertFalse( linked.next() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void openingLinkedCursorMustCloseExistingLinkedCursor() throws Exception
    {
        configureStandardPageCache();
        File file = file( "a" );

        // write case
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor parent = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            PageCursor linked = parent.openLinkedCursor( 1 );
            assertTrue( parent.next() );
            assertTrue( linked.next() );
            writeRecords( parent );
            writeRecords( linked );
            parent.openLinkedCursor( 2 );

            // should cause out of bounds condition because it should be closed by our opening of another linked cursor
            linked.putByte( 0, (byte) 1 );
            assertTrue( linked.checkAndClearBoundsFlag() );
        }

        // read case
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor parent = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            PageCursor linked = parent.openLinkedCursor( 1 );
            assertTrue( parent.next() );
            assertTrue( linked.next() );
            parent.openLinkedCursor( 2 );

            // should cause out of bounds condition because it should be closed by our opening of another linked cursor
            linked.getByte( 0 );
            assertTrue( linked.checkAndClearBoundsFlag() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void shouldRetryOnParentCursorMustReturnTrueIfLinkedCursorNeedsRetry() throws Exception
    {
        generateFileWithRecords( file( "a" ), recordsPerFilePage * 2, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor parentReader = pf.io( 0, PF_SHARED_READ_LOCK );
              PageCursor writer = pf.io( 1, PF_SHARED_WRITE_LOCK ) )
        {
            PageCursor linkedReader = parentReader.openLinkedCursor( 1 );
            assertTrue( parentReader.next() );
            assertTrue( linkedReader.next() );
            assertTrue( writer.next() );
            assertTrue( writer.next() ); // writer now moved on to page 2

            // parentReader shouldRetry should be true because the linked cursor needs retry
            assertTrue( parentReader.shouldRetry() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void checkAndClearBoundsFlagMustCheckAndClearLinkedCursor() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor parent = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( parent.next() );
            PageCursor linked = parent.openLinkedCursor( 1 );
            linked.raiseOutOfBounds();
            assertTrue( parent.checkAndClearBoundsFlag() );
            assertFalse( linked.checkAndClearBoundsFlag() );
        }
    }

    @Test
    public void shouldRetryMustClearBoundsFlagIfLinkedCursorNeedsRetry() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() ); // now at page id 0
            assertTrue( writer.next() ); // now at page id 1, 0 is unlocked
            assertTrue( writer.next() ); // now at page id 2, 1 is unlocked
            assertTrue( reader.next() ); // reader now at page id 0
            try ( PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
            {
                assertTrue( linkedReader.next() ); // linked reader now at page id 1
                assertTrue( writer.next( 1 ) ); // invalidate linked readers lock
                assertTrue( writer.next() ); // move writer out of the way
                reader.raiseOutOfBounds(); // raise bounds flag on parent reader
                assertTrue( reader.shouldRetry() ); // we must retry because linked reader was invalidated
                assertFalse( reader.checkAndClearBoundsFlag() ); // must return false because we are doing a retry
            }
        }
    }

    @Test
    public void checkAndClearCursorExceptionMustNotThrowIfNoExceptionIsSet() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.checkAndClearCursorException();
            }
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.checkAndClearCursorException();
                //noinspection StatementWithEmptyBody
                do
                {
                    // nothing
                }
                while ( cursor.shouldRetry() );
                cursor.checkAndClearCursorException();
            }
        }
    }

    @Test
    public void checkAndClearCursorExceptionMustThrowIfExceptionIsSet() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            String msg = "Boo" + ThreadLocalRandom.current().nextInt();
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.setCursorException( msg );
                cursor.checkAndClearCursorException();
                fail( "checkAndClearError on write cursor should have thrown" );
            }
            catch ( CursorException e )
            {
                assertThat( e.getMessage(), is( msg ) );
            }

            msg = "Boo" + ThreadLocalRandom.current().nextInt();
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.setCursorException( msg );
                cursor.checkAndClearCursorException();
                fail( "checkAndClearError on read cursor should have thrown" );
            }
            catch ( CursorException e )
            {
                assertThat( e.getMessage(), is( msg ) );
            }
        }
    }

    @Test
    public void checkAndClearCursorExceptionMustClearExceptionIfSet() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.setCursorException( "boo" );
                try
                {
                    cursor.checkAndClearCursorException();
                    fail( "checkAndClearError on write cursor should have thrown" );
                }
                catch ( CursorException ignore )
                {
                }
                cursor.checkAndClearCursorException();
            }

            try ( PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.setCursorException( "boo" );
                try
                {
                    cursor.checkAndClearCursorException();
                    fail( "checkAndClearError on read cursor should have thrown" );
                }
                catch ( CursorException ignore )
                {
                }
                cursor.checkAndClearCursorException();
            }
        }
    }

    @Test
    public void nextMustClearCursorExceptionIfSet() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.setCursorException( "boo" );
                assertTrue( cursor.next() );
                cursor.checkAndClearCursorException();
            }

            try ( PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next() );
                cursor.setCursorException( "boo" );
                assertTrue( cursor.next() );
                cursor.checkAndClearCursorException();
            }
        }
    }

    @Test
    public void nextWithIdMustClearCursorExceptionIfSet() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            try ( PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
            {
                assertTrue( cursor.next( 1 ) );
                cursor.setCursorException( "boo" );
                assertTrue( cursor.next( 2 ) );
                cursor.checkAndClearCursorException();
            }

            try ( PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
            {
                assertTrue( cursor.next( 1 ) );
                cursor.setCursorException( "boo" );
                assertTrue( cursor.next( 2 ) );
                cursor.checkAndClearCursorException();
            }
        }
    }

    @Test
    public void shouldRetryMustClearCursorExceptionIfItReturnsTrue() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() ); // now at page id 0
            assertTrue( writer.next() ); // now at page id 1, 0 is unlocked
            assertTrue( reader.next() ); // now at page id 0
            assertTrue( writer.next( 0 ) ); // invalidate the readers lock on page 0
            assertTrue( writer.next() ); // move writer out of the way
            reader.setCursorException( "boo" );
            assertTrue( reader.shouldRetry() ); // this should clear the cursor error
            reader.checkAndClearCursorException();
        }
    }

    @Test
    public void shouldRetryMustNotClearCursorExceptionIfItReturnsFalse() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( cursor.next() );
            do
            {
                cursor.setCursorException( "boo" );
            }
            while ( cursor.shouldRetry() );
            // The last shouldRetry has obviously returned 'false'
            try
            {
                cursor.checkAndClearCursorException();
                fail( "checkAndClearCursorException should have thrown" );
            }
            catch ( CursorException ignore )
            {
                // all good
            }
        }
    }

    @Test
    public void shouldRetryMustClearCursorExceptionIfLinkedShouldRetryReturnsTrue() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() ); // now at page id 0
            assertTrue( writer.next() ); // now at page id 1, 0 is unlocked
            assertTrue( writer.next() ); // now at page id 2, 1 is unlocked
            assertTrue( reader.next() ); // reader now at page id 0
            try ( PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
            {
                assertTrue( linkedReader.next() ); // linked reader now at page id 1
                assertTrue( writer.next( 1 ) ); // invalidate linked readers lock
                assertTrue( writer.next() ); // move writer out of the way
                reader.setCursorException( "boo" ); // raise cursor error on parent reader
                assertTrue( reader.shouldRetry() ); // we must retry because linked reader was invalidated
                reader.checkAndClearCursorException(); // must not throw because shouldRetry returned true
            }
        }
    }

    @Test
    public void shouldRetryMustClearLinkedCursorExceptionIfItReturnsTrue() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() ); // now at page id 0
            assertTrue( writer.next() ); // now at page id 1, 0 is unlocked
            assertTrue( writer.next() ); // now at page id 2, 1 is unlocked
            assertTrue( reader.next() ); // reader now at page id 0
            try ( PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
            {
                assertTrue( linkedReader.next() ); // linked reader now at page id 1
                linkedReader.setCursorException( "boo" );
                assertTrue( writer.next( 0 ) ); // invalidate the read lock held by the parent reader
                assertTrue( reader.shouldRetry() ); // this should clear the linked cursor error
                linkedReader.checkAndClearCursorException();
                reader.checkAndClearCursorException();
            }
        }
    }

    @Test
    public void shouldRetryMustClearLinkedCursorExceptionIfLinkedShouldRetryReturnsTrue() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() ); // now at page id 0
            assertTrue( writer.next() ); // now at page id 1, 0 is unlocked
            assertTrue( writer.next() ); // now at page id 2, 1 is unlocked
            assertTrue( reader.next() ); // reader now at page id 0
            try ( PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
            {
                assertTrue( linkedReader.next() ); // linked reader now at page id 1
                linkedReader.setCursorException( "boo" );
                assertTrue( writer.next( 1 ) ); // invalidate the read lock held by the linked reader
                assertTrue( reader.shouldRetry() ); // this should clear the linked cursor error
                linkedReader.checkAndClearCursorException();
                reader.checkAndClearCursorException();
            }
        }
    }

    @Test
    public void shouldRetryMustNotClearCursorExceptionIfBothItAndLinkedShouldRetryReturnsFalse() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK );
              PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
        {
            assertTrue( reader.next() );
            assertTrue( linkedReader.next() );
            do
            {
                reader.setCursorException( "boo" );
            }
            while ( reader.shouldRetry() );
            try
            {
                reader.checkAndClearCursorException();
                fail( "checkAndClearCursorException should have thrown" );
            }
            catch ( CursorException ignore )
            {
                // all good
            }
        }
    }

    @Test
    public void shouldRetryMustNotClearLinkedCursorExceptionIfBothItAndLinkedShouldRetryReturnsFalse() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK );
              PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
        {
            assertTrue( reader.next() );
            assertTrue( linkedReader.next() );
            do
            {
                linkedReader.setCursorException( "boo" );
            }
            while ( reader.shouldRetry() );
            try
            {
                reader.checkAndClearCursorException();
                fail( "checkAndClearCursorException should have thrown" );
            }
            catch ( CursorException ignore )
            {
                // all good
            }
        }
    }

    @Test
    public void checkAndClearCursorExceptionMustThrowIfLinkedCursorHasErrorSet() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            String msg = "Boo" + ThreadLocalRandom.current().nextInt();
            assertTrue( writer.next() );
            try ( PageCursor linkedWriter = writer.openLinkedCursor( 1 ) )
            {
                assertTrue( linkedWriter.next() );
                linkedWriter.setCursorException( msg );
                try
                {
                    writer.checkAndClearCursorException();
                    fail( "checkAndClearCursorException on writer should have thrown due to linked cursor error" );
                }
                catch ( CursorException e )
                {
                    assertThat( e.getMessage(), is( msg ) );
                }
            }

            msg = "Boo" + ThreadLocalRandom.current().nextInt();
            assertTrue( reader.next() );
            try ( PageCursor linkedReader = reader.openLinkedCursor( 1 ) )
            {
                assertTrue( linkedReader.next() );
                linkedReader.setCursorException( msg );
                try
                {
                    reader.checkAndClearCursorException();
                    fail( "checkAndClearCursorException on reader should have thrown due to linked cursor error" );
                }
                catch ( CursorException e )
                {
                    assertThat( e.getMessage(), is( msg ) );
                }
            }
        }
    }

    @Test
    public void checkAndClearCursorMustNotThrowIfErrorHasBeenSetButTheCursorHasBeenClosed() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );
            writer.setCursorException( "boo" );
            writer.close();
            writer.checkAndClearCursorException();

            PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK );
            assertTrue( reader.next() );
            reader.setCursorException( "boo" );
            reader.close();
            reader.checkAndClearCursorException();

            writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            PageCursor linkedWriter = writer.openLinkedCursor( 1 );
            assertTrue( linkedWriter.next() );
            linkedWriter.setCursorException( "boo" );
            writer.close();
            linkedWriter.checkAndClearCursorException();

            reader = pf.io( 0, PF_SHARED_READ_LOCK );
            PageCursor linkedReader = reader.openLinkedCursor( 1 );
            assertTrue( linkedReader.next() );
            linkedReader.setCursorException( "boo" );
            reader.close();
            linkedReader.checkAndClearCursorException();
        }
    }

    @Test
    public void openingLinkedCursorOnClosedCursorMustThrow() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( writer.next() );
            writer.close();
            try
            {
                writer.openLinkedCursor( 1 );
                fail( "opening linked cursor on closed write cursor should have thrown" );
            }
            catch ( IllegalStateException ignore )
            {
                // all good
            }

            PageCursor reader = pf.io( 0, PF_SHARED_WRITE_LOCK );
            assertTrue( reader.next() );
            reader.close();
            try
            {
                reader.openLinkedCursor( 1 );
                fail( "opening linked cursor on closed reader cursor should have thrown" );
            }
            catch ( IllegalStateException ignore )
            {
                // all good
            }
        }
    }

    @Test
    public void settingNullCursorExceptionMustThrow() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() );
            try
            {
                writer.setCursorException( null );
                fail( "setting null cursor error on write cursor should have thrown" );
            }
            catch ( Exception e )
            {
                // all good
            }

            assertTrue( reader.next() );
            try
            {
                reader.setCursorException( null );
                fail( "setting null cursor error in read cursor should have thrown" );
            }
            catch ( Exception e )
            {
                // all good
            }
        }
    }

    @Test
    public void clearCursorExceptionMustUnsetErrorCondition() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() );
            writer.setCursorException( "boo" );
            writer.clearCursorException();
            writer.checkAndClearCursorException();

            assertTrue( reader.next() );
            reader.setCursorException( "boo" );
            reader.clearCursorException();
            reader.checkAndClearCursorException();
        }
    }

    @Test
    public void clearCursorExceptionMustUnsetErrorConditionOnLinkedCursor() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor writer = pf.io( 0, PF_SHARED_WRITE_LOCK );
              PageCursor reader = pf.io( 0, PF_SHARED_READ_LOCK ) )
        {
            assertTrue( writer.next() );
            PageCursor linkedWriter = writer.openLinkedCursor( 1 );
            assertTrue( linkedWriter.next() );
            linkedWriter.setCursorException( "boo" );
            writer.clearCursorException();
            writer.checkAndClearCursorException();

            assertTrue( reader.next() );
            PageCursor linkedReader = reader.openLinkedCursor( 1 );
            assertTrue( linkedReader.next() );
            linkedReader.setCursorException( "boo" );
            reader.clearCursorException();
            reader.checkAndClearCursorException();
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readableByteChannelMustBeOpenUntilClosed() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            ReadableByteChannel channel;
            try ( ReadableByteChannel ch = pf.openReadableByteChannel() )
            {
                assertTrue( ch.isOpen() );
                channel = ch;
            }
            assertFalse( channel.isOpen() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readableByteChannelMustReadAllBytesInFile() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize );
              ReadableByteChannel channel = pf.openReadableByteChannel() )
        {
            verifyRecordsInFile( channel, recordCount );
        }
    }

    @RepeatRule.Repeat( times = 20 )
    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readableByteChannelMustReadAllBytesInFileConsistently() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            RandomAdversary adversary = new RandomAdversary( 0.9, 0, 0 );
            AdversarialPagedFile apf = new AdversarialPagedFile( pf, adversary );
            try ( ReadableByteChannel channel = apf.openReadableByteChannel() )
            {
                verifyRecordsInFile( channel, recordCount );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void readingFromClosedReadableByteChannelMustThrow() throws Exception
    {
        File file = file( "a" );
        generateFileWithRecords( file, recordCount, recordSize );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            ReadableByteChannel channel = pf.openReadableByteChannel();
            channel.close();
            expectedException.expect( ClosedChannelException.class );
            channel.read( ByteBuffer.allocate( recordSize ) );
            fail( "That read should have thrown" );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writableByteChannelMustBeOpenUntilClosed() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            WritableByteChannel channel;
            try ( WritableByteChannel ch = pf.openWritableByteChannel() )
            {
                assertTrue( ch.isOpen() );
                channel = ch;
            }
            assertFalse( channel.isOpen() );
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writableByteChannelMustWriteAllBytesInFile() throws Exception
    {
        File file = file( "a" );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            try ( WritableByteChannel channel = pf.openWritableByteChannel() )
            {
                generateFileWithRecords( channel, recordCount, recordSize );
            }
            try ( ReadableByteChannel channel = pf.openReadableByteChannel() )
            {
                verifyRecordsInFile( channel, recordCount );
            }
        }
    }

    @Test( timeout = SHORT_TIMEOUT_MILLIS )
    public void writingToClosedWritableByteChannelMustThrow() throws Exception
    {
        File file = file( "a" );
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file, filePageSize ) )
        {
            WritableByteChannel channel = pf.openWritableByteChannel();
            channel.close();
            expectedException.expect( ClosedChannelException.class );
            channel.write( ByteBuffer.allocate( recordSize ) );
            fail( "That read should have thrown" );
        }
    }

    @Test
    public void sizeOfEmptyFileMustBeZero() throws Exception
    {
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize ) )
        {
            assertThat( pf.fileSize(), is( 0L ) );
        }
    }

    @Test
    public void fileSizeMustIncreaseInPageIncrements() throws Exception
    {
        long increment = filePageSize;
        configureStandardPageCache();
        try ( PagedFile pf = pageCache.map( file( "a" ), filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK ) )
        {
            assertTrue( cursor.next() );
            assertThat( pf.fileSize(), is( increment ) );
            assertTrue( cursor.next() );
            assertThat( pf.fileSize(), is( 2 * increment ) );
        }
    }

    @Test
    public void streamFilesRecursiveMustBeEmptyForEmptyBaseDirectory() throws Exception
    {
        configureStandardPageCache();
        File dir = existingDirectory( "dir" );
        assertThat( pageCache.streamFilesRecursive( dir ).count(), is( 0L ) );
    }

    @Test
    public void streamFilesRecursiveMustListAllFilesInBaseDirectory() throws Exception
    {
        configureStandardPageCache();
        File a = existingFile( "a" );
        File b = existingFile( "b" );
        File c = existingFile( "c" );
        Stream<FileHandle> stream = pageCache.streamFilesRecursive( a.getParentFile() );
        List<File> filepaths = stream.map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder( a.getCanonicalFile(), b.getCanonicalFile(), c.getCanonicalFile() ) );
    }

    @Test
    public void streamFilesRecursiveMustListAllFilesInSubDirectories() throws Exception
    {
        configureStandardPageCache();
        File sub1 = existingDirectory( "sub1" );
        File sub2 = existingDirectory( "sub2" );
        File a = existingFile( "a" );
        File b = new File( sub1, "b" );
        File c = new File( sub2, "c" );
        ensureExists( b );
        ensureExists( c );

        Stream<FileHandle> stream = pageCache.streamFilesRecursive( a.getParentFile() );
        List<File> filepaths = stream.map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder( a.getCanonicalFile(), b.getCanonicalFile(), c.getCanonicalFile() ) );
    }

    @Test
    public void streamFilesRecursiveMustNotListSubDirectories() throws Exception
    {
        configureStandardPageCache();
        File sub1 = existingDirectory( "sub1" );
        File sub2 = existingDirectory( "sub2" );
        File sub2sub1 = new File( sub2, "sub1");
        ensureDirectoryExists( sub2sub1 );
        existingDirectory( "sub3" ); // must not be observed in the stream
        File a = existingFile( "a" );
        File b = new File( sub1, "b" );
        File c = new File( sub2, "c" );
        ensureExists( b );
        ensureExists( c );

        Stream<FileHandle> stream = pageCache.streamFilesRecursive( a.getParentFile() );
        List<File> filepaths = stream.map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder( a.getCanonicalFile(), b.getCanonicalFile(), c.getCanonicalFile() ) );
    }

    @Test
    public void streamFilesRecursiveFilePathsMustBeCanonical() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File a = new File( new File( new File( sub, ".." ), "sub" ), "a" );
        ensureExists( a );

        Stream<FileHandle> stream = pageCache.streamFilesRecursive( sub.getParentFile() );
        List<File> filepaths = stream.map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder(
                a.getCanonicalFile(), // file in our sub directory
                file( "a" ).getCanonicalFile() ) ); // this file is always created by the test setup
    }

    @Test
    public void streamFilesRecursiveMustListSingleFileGivenAsBase() throws Exception
    {
        configureStandardPageCache();
        existingDirectory( "sub" ); // must not be observed
        existingFile( "sub/x" ); // must not be observed
        File a = file( "a" );

        Stream<FileHandle> stream = pageCache.streamFilesRecursive( a );
        List<File> filepaths = stream.map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder( a.getCanonicalFile() ) ); // note that we don't go into 'sub'
    }

    @Test
    public void streamFilesRecursiveListedSingleFileMustHaveCanonicalPath() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        existingFile( "sub/x" ); // we query specifically for 'a', so this must not be listed
        File a = file( "a" );
        File queryForA = new File( new File( sub, ".." ), "a" );

        Stream<FileHandle> stream = pageCache.streamFilesRecursive( queryForA );
        List<File> filepaths = stream.map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder( a.getCanonicalFile() ) ); // note that we don't go into 'sub'
    }

    @Test
    public void streamFilesRecursiveMustThrowOnNonExistingBasePath() throws Exception
    {
        configureStandardPageCache();
        File nonExisting = file( "nonExisting" );
        expectedException.expect( NoSuchFileException.class );
        pageCache.streamFilesRecursive( nonExisting );
    }

    @Test
    public void streamFilesRecursiveMustRenameFiles() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = file( "b" ); // does not yet exist
        File base = a.getParentFile();
        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( base )::iterator;
        for ( FileHandle fh : handles )
        {
            fh.rename( b );
        }
        List<File> filepaths = pageCache.streamFilesRecursive( base ).map( FileHandle::getFile ).collect( toList() );
        assertThat( filepaths, containsInAnyOrder( b.getCanonicalFile() ) );
    }

    @Test
    public void streamFilesRecursiveMustDeleteFiles() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = file( "b" );
        File c = file( "c" );
        ensureExists( a );
        ensureExists( b );
        ensureExists( c );

        File base = a.getParentFile();
        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( base )::iterator;
        for ( FileHandle fh : handles )
        {
            fh.delete();
        }

        assertFalse( fs.fileExists( a ) );
        assertFalse( fs.fileExists( b ) );
        assertFalse( fs.fileExists( c ) );
    }

    @Test
    public void streamFilesRecursiveMustThrowWhenRenamingMappedSourceFile() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = file( "b" );
        try ( PagedFile ignore = pageCache.map( a, filePageSize ) )
        {
            Iterable<FileHandle> handles = pageCache.streamFilesRecursive( a.getParentFile() )::iterator;
            for ( FileHandle handle : handles )
            {
                expectedException.expect( FileIsMappedException.class );
                handle.rename( b );
            }
        }
    }

    @Test
    public void streamFilesRecursiveMustThrowWhenRenamingMappedTargetFile() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = existingFile( "b" );
        try ( PagedFile ignore = pageCache.map( b, filePageSize ) )
        {
            Stream<FileHandle> streamOfA =
                    pageCache.streamFilesRecursive( a.getParentFile() ).filter( hasFile( a ) );
            Iterable<FileHandle> handles = streamOfA::iterator;
            for ( FileHandle handle : handles )
            {
                expectedException.expect( FileIsMappedException.class );
                handle.rename( b );
            }
        }
    }

    private Predicate<FileHandle> hasFile( File a )
    {
        return fh -> fh.getFile().equals( a );
    }

    @Test
    public void streamFilesRecursiveMustThrowWhenDeletingMappedFile() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        try ( PagedFile ignore = pageCache.map( a, filePageSize ) )
        {
            FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
            expectedException.expect( FileIsMappedException.class );
            handle.delete();
        }
    }

    @Test
    public void streamFilesRecursiveMustThrowWhenDeletingNonExistingFile() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        fs.deleteFile( a );
        expectedException.expect( NoSuchFileException.class );
        handle.delete(); // must throw
    }

    @Test
    public void streamFilesRecursiveMustThrowWhenTargetFileOfRenameAlreadyExists() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = existingFile( "b" );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        expectedException.expect( FileAlreadyExistsException.class );
        handle.rename( b );
    }

    @Test
    public void streamFilesRecursiveMustNotThrowWhenTargetFileOfRenameAlreadyExistsAndUsingReplaceExisting()
            throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = existingFile( "b" );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b, StandardCopyOption.REPLACE_EXISTING );
    }

    @Test
    public void streamFilesRecursiveMustDeleteSubDirectoriesEmptiedByFileRename() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File x = new File( sub, "x" );
        ensureExists( x );
        File target = file( "target" );

        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( sub )::iterator;
        for ( FileHandle handle : handles )
        {
            handle.rename( target );
        }

        assertFalse( fs.isDirectory( sub ) );
        assertFalse( fs.fileExists( sub ) );
    }

    @Test
    public void streamFilesRecursiveMustDeleteMultipleLayersOfSubDirectoriesIfTheyBecomeEmptyByRename() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File subsub = new File( sub, "subsub" );
        ensureDirectoryExists( subsub );
        File x = new File( subsub, "x" );
        ensureExists( x );
        File target = file( "target" );

        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( sub )::iterator;
        for ( FileHandle handle : handles )
        {
            handle.rename( target );
        }

        assertFalse( fs.isDirectory( subsub ) );
        assertFalse( fs.fileExists( subsub ) );
        assertFalse( fs.isDirectory( sub ) );
        assertFalse( fs.fileExists( sub ) );
    }

    @Test
    public void streamFilesRecursiveMustNotDeleteDirectoriesAboveBaseDirectoryIfTheyBecomeEmptyByRename() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File subsub = new File( sub, "subsub" );
        File subsubsub = new File( subsub, "subsubsub" );
        ensureDirectoryExists( subsub );
        ensureDirectoryExists( subsubsub );
        File x = new File( subsubsub, "x" );
        ensureExists( x );
        File target = file( "target" );

        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( subsub )::iterator;
        for ( FileHandle handle : handles )
        {
            handle.rename( target );
        }

        assertFalse( fs.fileExists( subsubsub ) );
        assertFalse( fs.isDirectory( subsubsub ) );
        assertFalse( fs.fileExists( subsub ) );
        assertFalse( fs.isDirectory( subsub ) );
        assertTrue( fs.fileExists( sub ) );
        assertTrue( fs.isDirectory( sub ) );
    }

    @Test
    public void streamFilesRecursiveMustDeleteSubDirectoriesEmptiedByFileDelete() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File x = new File( sub, "x" );
        ensureExists( x );

        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( sub )::iterator;
        for ( FileHandle handle : handles )
        {
            handle.delete();
        }

        assertFalse( fs.isDirectory( sub ) );
        assertFalse( fs.fileExists( sub ) );
    }

    @Test
    public void streamFilesRecursiveMustDeleteMultipleLayersOfSubDirectoriesIfTheyBecomeEmptyByDelete() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File subsub = new File( sub, "subsub" );
        ensureDirectoryExists( subsub );
        File x = new File( subsub, "x" );
        ensureExists( x );

        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( sub )::iterator;
        for ( FileHandle handle : handles )
        {
            handle.delete();
        }

        assertFalse( fs.isDirectory( subsub ) );
        assertFalse( fs.fileExists( subsub ) );
        assertFalse( fs.isDirectory( sub ) );
        assertFalse( fs.fileExists( sub ) );
    }

    @Test
    public void streamFilesRecursiveMustNotDeleteDirectoriesAboveBaseDirectoryIfTheyBecomeEmptyByDelete() throws Exception
    {
        configureStandardPageCache();
        File sub = existingDirectory( "sub" );
        File subsub = new File( sub, "subsub" );
        File subsubsub = new File( subsub, "subsubsub" );
        ensureDirectoryExists( subsub );
        ensureDirectoryExists( subsubsub );
        File x = new File( subsubsub, "x" );
        ensureExists( x );

        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( subsub )::iterator;
        for ( FileHandle handle : handles )
        {
            handle.delete();
        }

        assertFalse( fs.fileExists( subsubsub ) );
        assertFalse( fs.isDirectory( subsubsub ) );
        assertFalse( fs.fileExists( subsub ) );
        assertFalse( fs.isDirectory( subsub ) );
        assertTrue( fs.fileExists( sub ) );
        assertTrue( fs.isDirectory( sub ) );
    }

    @Test
    public void streamFilesRecursiveMustCreateMissingPathDirectoriesImpliedByFileRename() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File sub = file( "sub" ); // does not exists
        File target = new File( sub, "b" );

        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( target );

        assertTrue( fs.isDirectory( sub ) );
        assertTrue( fs.fileExists( target ) );
    }

    @Test
    public void streamFilesRecursiveMustNotSeeFilesLaterCreatedBaseDirectory() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        Stream<FileHandle> stream = pageCache.streamFilesRecursive( a.getParentFile() );
        File b = existingFile( "b" );
        Set<File> files = stream.map( FileHandle::getFile ).collect( toSet() );
        assertThat( files, contains( a ) );
        assertThat( files, not( contains( b ) ) );
    }

    @Test
    public void streamFilesRecursiveMustNotSeeFilesRenamedIntoBaseDirectory() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File sub = existingDirectory( "sub" );
        File x = new File( sub, "x" );
        ensureExists( x );
        File target = file( "target" );
        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( a.getParentFile() )::iterator;
        Set<File> observedFiles = new HashSet<>();
        for ( FileHandle handle : handles )
        {
            File file = handle.getFile();
            observedFiles.add( file );
            if ( file.equals( x ) )
            {
                handle.rename( target );
            }
        }
        assertThat( observedFiles, containsInAnyOrder( a, x ) );
    }

    @Test
    public void streamFilesRecursiveMustNotSeeFilesRenamedIntoSubDirectory() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File sub = existingDirectory( "sub" );
        File target = new File( sub, "target" );
        Iterable<FileHandle> handles = pageCache.streamFilesRecursive( a.getParentFile() )::iterator;
        Set<File> observedFiles = new HashSet<>();
        for ( FileHandle handle : handles )
        {
            File file = handle.getFile();
            observedFiles.add( file );
            if ( file.equals( a ) )
            {
                handle.rename( target );
            }
        }
        assertThat( observedFiles, containsInAnyOrder( a ) );
    }

    @Test
    public void streamFilesRecursiveRenameMustCanonicaliseSourceFile() throws Exception
    {
        configureStandardPageCache();
        // File 'a' should canonicalise from 'a/poke/..' to 'a', which is a file that exists.
        // Thus, this should not throw a NoSuchFileException.
        File a = new File( new File( file( "a" ), "poke" ), ".." );
        File b = file( "b" );

        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b ); // must not throw
    }

    @Test
    public void streamFilesRecursiveRenameMustCanonicaliseTargetFile() throws Exception
    {
        configureStandardPageCache();
        // File 'b' should canonicalise from 'b/poke/..' to 'b', which is a file that doesn't exists.
        // Thus, this should not throw a NoSuchFileException for the 'poke' directory.
        File a = file( "a" );
        File b = new File( new File( file( "b" ), "poke" ), ".." );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b );
    }

    @Test
    public void streamFilesRecursiveRenameTargetFileMustBeMappable() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = file( "b" );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b );
        pageCache.map( b, filePageSize ).close();
    }

    @Test
    public void streamFilesRecursiveSourceFileMustNotBeMappableAfterRename() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = file( "b" );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b );
        expectedException.expect( NoSuchFileException.class );
        pageCache.map( a, filePageSize );
        fail( "pageCache.map should have thrown" );
    }

    @Test
    public void streamFilesRecursiveRenameMustNotChangeSourceFileContents() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = file( "b" );
        generateFileWithRecords( a, recordCount, recordSize );
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b );
        verifyRecordsInFile( b, recordCount );
    }

    @Test
    public void streamFilesRecursiveRenameMustNotChangeSourceFileContentsWithReplaceExisting() throws Exception
    {
        configureStandardPageCache();
        File a = file( "a" );
        File b = existingFile( "b" );
        generateFileWithRecords( a, recordCount, recordSize );
        generateFileWithRecords( b, recordCount + recordsPerFilePage, recordSize );

        // Fill 'b' with random data
        try ( PagedFile pf = pageCache.map( b, filePageSize );
              PageCursor cursor = pf.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW ) )
        {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            while ( cursor.next() )
            {
                int pageSize = cursor.getCurrentPageSize();
                for ( int i = 0; i < pageSize; i++ )
                {
                    cursor.putByte( i, (byte) rng.nextInt() );
                }
            }
        }

        // Do the rename
        FileHandle handle = pageCache.streamFilesRecursive( a ).findAny().get();
        handle.rename( b, REPLACE_EXISTING );

        // Then verify that the old random data we put in 'b' has been replaced with the contents of 'a'
        verifyRecordsInFile( b, recordCount );
    }
}
