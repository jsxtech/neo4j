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
package org.neo4j.bolt.transport;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;

import org.neo4j.helpers.ListenSocketAddress;
import org.neo4j.logging.LogProvider;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Implements a transport for the Neo4j Messaging Protocol that uses good old regular sockets.
 */
public class SocketTransport implements NettyServer.ProtocolInitializer
{
    private final ListenSocketAddress address;
    private final SslContext sslCtx;
    private final boolean encryptionRequired;
    private LogProvider logging;
    private final Map<Long, BiFunction<Channel, Boolean, BoltProtocol>> protocolVersions;

    public SocketTransport( ListenSocketAddress address, SslContext sslCtx, boolean encryptionRequired, LogProvider logging,
                            Map<Long, BiFunction<Channel, Boolean, BoltProtocol>> protocolVersions )
    {
        this.address = address;
        this.sslCtx = sslCtx;
        this.encryptionRequired = encryptionRequired;
        this.logging = logging;
        this.protocolVersions = protocolVersions;
    }

    @Override
    public ChannelInitializer<SocketChannel> channelInitializer()
    {
        return new ChannelInitializer<SocketChannel>()
        {
            @Override
            public void initChannel( SocketChannel ch ) throws Exception
            {
                ch.config().setAllocator( PooledByteBufAllocator.DEFAULT );
                ch.pipeline().addLast(
                        new TransportSelectionHandler( sslCtx, encryptionRequired, false, logging, protocolVersions ) );
            }
        };
    }

    @Override
    public ListenSocketAddress address()
    {
        return address;
    }
}
