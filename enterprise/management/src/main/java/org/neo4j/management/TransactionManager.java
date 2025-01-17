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
package org.neo4j.management;

import org.neo4j.jmx.Description;
import org.neo4j.jmx.ManagementInterface;

@ManagementInterface( name = TransactionManager.NAME )
@Description( "Information about the Neo4j transaction manager" )
public interface TransactionManager
{
    final String NAME = "Transactions";

    @Description( "The number of currently open transactions" )
    long getNumberOfOpenTransactions();

    @Description( "The highest number of transactions ever opened concurrently" )
    long getPeakNumberOfConcurrentTransactions();

    @Description( "The total number started transactions" )
    long getNumberOfOpenedTransactions();

    @Description( "The total number of committed transactions" )
    long getNumberOfCommittedTransactions();

    @Description( "The total number of rolled back transactions" )
    long getNumberOfRolledBackTransactions();

    @Description( "The id of the latest committed transaction" )
    long getLastCommittedTxId();
}
