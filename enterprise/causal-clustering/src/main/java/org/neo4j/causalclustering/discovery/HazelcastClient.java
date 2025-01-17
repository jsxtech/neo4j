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
package org.neo4j.causalclustering.discovery;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.neo4j.causalclustering.core.CausalClusteringSettings;
import org.neo4j.causalclustering.helper.RobustJobSchedulerWrapper;
import org.neo4j.causalclustering.identity.MemberId;
import org.neo4j.helpers.AdvertisedSocketAddress;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.util.JobScheduler;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.neo4j.causalclustering.discovery.HazelcastClusterTopology.READ_REPLICA_BOLT_ADDRESS_MAP_NAME;
import static org.neo4j.causalclustering.discovery.HazelcastClusterTopology.extractCatchupAddressesMap;
import static org.neo4j.causalclustering.discovery.HazelcastClusterTopology.getCoreTopology;
import static org.neo4j.causalclustering.discovery.HazelcastClusterTopology.getReadReplicaTopology;

class HazelcastClient extends LifecycleAdapter implements TopologyService
{
    private final Log log;
    private final ClientConnectorAddresses connectorAddresses;
    private final RobustHazelcastWrapper hzInstance;
    private final RobustJobSchedulerWrapper scheduler;
    private final Config config;

    private final long timeToLive;
    private final long refreshPeriod;

    private JobScheduler.JobHandle keepAliveJob;
    private JobScheduler.JobHandle refreshTopologyJob;

    private volatile Map<MemberId,AdvertisedSocketAddress> catchupAddressMap = new HashMap<>();
    private volatile CoreTopology coreTopology = CoreTopology.EMPTY;
    private volatile ReadReplicaTopology rrTopology = ReadReplicaTopology.EMPTY;

    HazelcastClient( HazelcastConnector connector, JobScheduler scheduler, LogProvider logProvider, Config config )
    {
        this.hzInstance = new RobustHazelcastWrapper( connector );
        this.config = config;
        this.log = logProvider.getLog( getClass() );
        this.scheduler = new RobustJobSchedulerWrapper( scheduler, log );
        this.connectorAddresses = ClientConnectorAddresses.extractFromConfig( config );
        this.timeToLive = config.get( CausalClusteringSettings.read_replica_time_to_live );
        this.refreshPeriod = config.get( CausalClusteringSettings.cluster_topology_refresh );
    }

    @Override
    public CoreTopology coreServers()
    {
        return coreTopology;
    }

    @Override
    public ReadReplicaTopology readReplicas()
    {
        return rrTopology;
    }

    @Override
    public Optional<AdvertisedSocketAddress> findCatchupAddress( MemberId memberId )
    {
        return Optional.ofNullable( catchupAddressMap.get( memberId ) );
    }

    /**
     * Caches the topology so that the lookups are fast.
     */
    private void refreshTopology() throws HazelcastInstanceNotActiveException
    {
        coreTopology = hzInstance.apply( ( hz ) -> getCoreTopology( hz, config, log ) );
        rrTopology = hzInstance.apply( ( hz ) -> getReadReplicaTopology( hz, log ) );
        catchupAddressMap = extractCatchupAddressesMap( coreTopology, rrTopology );
    }

    @Override
    public void start() throws Throwable
    {
        keepAliveJob = scheduler.scheduleRecurring( "KeepAlive", timeToLive / 3, this::keepReadReplicaAlive );
        refreshTopologyJob = scheduler.scheduleRecurring( "TopologyRefresh", refreshPeriod, this::refreshTopology );
    }

    @Override
    public void stop() throws Throwable
    {
        keepAliveJob.cancel( true );
        refreshTopologyJob.cancel( true );
        disconnectFromCore();
    }

    private void disconnectFromCore()
    {
        try
        {
            String uuid = hzInstance.apply( hzInstance -> hzInstance.getLocalEndpoint().getUuid() );
            hzInstance.apply( hz -> hz.getMap( READ_REPLICA_BOLT_ADDRESS_MAP_NAME ).remove( uuid ) );
            hzInstance.shutdown();
        }
        catch ( Throwable e )
        {
            // Hazelcast is not able to stop correctly sometimes and throws a bunch of different exceptions
            // let's simply log the current problem but go on with our shutdown
            log.warn( "Unable to shutdown hazelcast cleanly", e );
        }
    }

    private void keepReadReplicaAlive() throws HazelcastInstanceNotActiveException
    {
        hzInstance.perform( hazelcastInstance ->
        {
            String uuid = hazelcastInstance.getLocalEndpoint().getUuid();
            String addresses = connectorAddresses.toString();
            log.debug( "Adding read replica into cluster (%s -> %s)", uuid, addresses );

            // this needs to be last as when we read from it in HazelcastClusterTopology.readReplicas
            // we assume that all the other maps have been populated if an entry exists in this one
            hazelcastInstance.getMap( READ_REPLICA_BOLT_ADDRESS_MAP_NAME )
                    .put( uuid, addresses, timeToLive, MILLISECONDS );
        } );
    }
}
