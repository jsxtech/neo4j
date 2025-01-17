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
package org.neo4j.com;

import org.neo4j.logging.Log;

public class LoggingResourcePoolMonitor extends ResourcePool.Monitor.Adapter<ChannelContext>
{
    private final Log msgLog;
    private int lastCurrentPeakSize = -1;
    private int lastTargetSize = -1;

    public LoggingResourcePoolMonitor( Log msgLog )
    {
        this.msgLog = msgLog;
    }

    @Override
    public void updatedCurrentPeakSize( int currentPeakSize )
    {
        if ( lastCurrentPeakSize != currentPeakSize )
        {
            msgLog.debug( "ResourcePool updated currentPeakSize to " + currentPeakSize );
            lastCurrentPeakSize = currentPeakSize;
        }
    }

    @Override
    public void created( ChannelContext resource )
    {
        msgLog.debug( "ResourcePool create resource " + resource );
    }

    @Override
    public void updatedTargetSize( int targetSize )
    {
        if ( lastTargetSize != targetSize )
        {
            msgLog.debug( "ResourcePool updated targetSize to " + targetSize );
            lastTargetSize = targetSize;
        }
    }
}
