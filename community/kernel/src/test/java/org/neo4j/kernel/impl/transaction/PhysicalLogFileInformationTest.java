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
package org.neo4j.kernel.impl.transaction;

import org.junit.Test;

import org.neo4j.kernel.impl.transaction.log.LogHeaderCache;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFileInformation;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogFiles;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.entry.LogHeader;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PhysicalLogFileInformationTest
{

    private PhysicalLogFiles logFiles = mock( PhysicalLogFiles.class );
    private LogHeaderCache logHeaderCache = mock( LogHeaderCache.class );
    private TransactionIdStore transactionIdStore = mock( TransactionIdStore.class );
    private PhysicalLogFileInformation.LogVersionToTimestamp
            logVersionToTimestamp = mock( PhysicalLogFileInformation.LogVersionToTimestamp.class );

    @Test
    public void shouldReadAndCacheFirstCommittedTransactionIdForAGivenVersionWhenNotCached() throws Exception
    {
        PhysicalLogFileInformation info = new PhysicalLogFileInformation( logFiles, logHeaderCache, transactionIdStore::getLastCommittedTransactionId,
                logVersionToTimestamp );
        long expected = 5;

        long version = 10L;
        when( logHeaderCache.getLogHeader( version ) ).thenReturn( null );
        when( logFiles.versionExists( version ) ).thenReturn( true );
        when( logFiles.extractHeader( version ) ).thenReturn(
                new LogHeader( (byte) -1/*ignored*/, -1L/*ignored*/, expected - 1L )
        );

        long firstCommittedTxId = info.getFirstEntryId( version );
        assertEquals( expected, firstCommittedTxId );
        verify( logHeaderCache, times( 1 ) ).putHeader( version, expected - 1 );
    }

    @Test
    public void shouldReadFirstCommittedTransactionIdForAGivenVersionWhenCached() throws Exception
    {
        PhysicalLogFileInformation info = new PhysicalLogFileInformation( logFiles, logHeaderCache, transactionIdStore::getLastCommittedTransactionId,
                logVersionToTimestamp );
        long expected = 5;

        long version = 10L;
        when( logHeaderCache.getLogHeader( version ) ).thenReturn( expected - 1 );

        long firstCommittedTxId = info.getFirstEntryId( version );
        assertEquals( expected, firstCommittedTxId );
    }

    @Test
    public void shouldReadAndCacheFirstCommittedTransactionIdWhenNotCached() throws Exception
    {
        PhysicalLogFileInformation info = new PhysicalLogFileInformation( logFiles, logHeaderCache, transactionIdStore::getLastCommittedTransactionId,
                logVersionToTimestamp );
        long expected = 5;

        long version = 10L;
        when( logFiles.getHighestLogVersion() ).thenReturn( version );
        when( logHeaderCache.getLogHeader( version ) ).thenReturn( null );
        when( logFiles.versionExists( version ) ).thenReturn( true );
        when( logFiles.extractHeader( version ) ).thenReturn(
                new LogHeader( (byte) -1/*ignored*/, -1L/*ignored*/, expected - 1L )
        );
        when( logFiles.hasAnyEntries( version ) ).thenReturn( true );

        long firstCommittedTxId = info.getFirstExistingEntryId();
        assertEquals( expected, firstCommittedTxId );
        verify( logHeaderCache, times( 1 ) ).putHeader( version, expected - 1 );
    }

    @Test
    public void shouldReadFirstCommittedTransactionIdWhenCached() throws Exception
    {
        PhysicalLogFileInformation info = new PhysicalLogFileInformation( logFiles, logHeaderCache, transactionIdStore::getLastCommittedTransactionId,
                logVersionToTimestamp );
        long expected = 5;

        long version = 10L;
        when( logFiles.getHighestLogVersion() ).thenReturn( version );
        when( logFiles.versionExists( version ) ).thenReturn( true );
        when( logHeaderCache.getLogHeader( version ) ).thenReturn( expected -1 );
        when( logFiles.hasAnyEntries( version ) ).thenReturn( true );

        long firstCommittedTxId = info.getFirstExistingEntryId();
        assertEquals( expected, firstCommittedTxId );
    }

    @Test
    public void shouldReturnNothingWhenThereAreNoTransactions() throws Exception
    {
        PhysicalLogFileInformation info = new PhysicalLogFileInformation( logFiles, logHeaderCache, transactionIdStore::getLastCommittedTransactionId,
                logVersionToTimestamp );

        long version = 10L;
        when( logFiles.getHighestLogVersion() ).thenReturn( version );
        when( logFiles.hasAnyEntries( version ) ).thenReturn( false );

        long firstCommittedTxId = info.getFirstExistingEntryId();
        assertEquals( -1, firstCommittedTxId );
    }
}
