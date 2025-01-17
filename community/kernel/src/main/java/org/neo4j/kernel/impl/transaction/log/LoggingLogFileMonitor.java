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
package org.neo4j.kernel.impl.transaction.log;

import java.io.File;

import org.neo4j.kernel.impl.transaction.log.rotation.LogRotation;
import org.neo4j.kernel.recovery.Recovery;
import org.neo4j.kernel.recovery.PositionToRecoverFrom;
import org.neo4j.logging.Log;

import static java.lang.String.format;

public class LoggingLogFileMonitor implements
        PhysicalLogFile.Monitor,
        LogRotation.Monitor,
        Recovery.Monitor,
        PositionToRecoverFrom.Monitor
{
    private long firstTransactionRecovered = -1, lastTransactionRecovered;
    private final Log log;

    public LoggingLogFileMonitor( Log log )
    {
        this.log = log;
    }

    @Override
    public void recoveryRequired( LogPosition startPosition )
    {
        log.info( "Recovery required from position " + startPosition );
    }

    @Override
    public void recoveryCompleted( int numberOfRecoveredTransactions )
    {
        if ( numberOfRecoveredTransactions != 0 )
        {
            log.info( format( "Recovery completed. %d transactions, first:%d, last:%d recovered",
                    numberOfRecoveredTransactions, firstTransactionRecovered, lastTransactionRecovered ) );
        }
        else
        {
            log.info( "No recovery required" );
        }
    }

    @Override
    public void startedRotating( long currentVersion )
    {
        log.info( format( "Rotating log version:%d", currentVersion ) );
    }

    @Override
    public void finishedRotating( long currentVersion )
    {
        log.info( format( "Finished rotating log version:%d", currentVersion ) );
    }

    @Override
    public void transactionRecovered( long txId )
    {
        if ( firstTransactionRecovered == -1 )
        {
            firstTransactionRecovered = txId;
        }
        lastTransactionRecovered = txId;
    }

    @Override
    public void opened( File logFile, long logVersion, long lastTransactionId, boolean clean )
    {
        log.info( format( "Opened logical log [%s] version=%d, lastTxId=%d (%s)",
                logFile, logVersion, lastTransactionId,  (clean ? "clean" : "recovered") ) );
    }

    @Override
    public void noCommitsAfterLastCheckPoint( LogPosition logPosition )
    {
        log.info( format( "No commits found after last check point (which is at %s)",
                logPosition != null ? logPosition.toString() : "<no log position given>" ) );
    }

    @Override
    public void commitsAfterLastCheckPoint( LogPosition logPosition, long firstTxIdAfterLastCheckPoint )
    {
        log.info( format(
                "Commits found after last check point (which is at %s). First txId after last checkpoint: %d ",
                logPosition, firstTxIdAfterLastCheckPoint ) );
    }

    @Override
    public void noCheckPointFound()
    {
        log.info( "No check point found in transaction log" );
    }
}
