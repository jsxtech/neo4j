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
package org.neo4j.kernel.configuration;

import org.junit.Test;

import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.ListenSocketAddress;

import static org.junit.Assert.assertEquals;

import static org.neo4j.helpers.collection.MapUtil.stringMap;
import static org.neo4j.kernel.configuration.Settings.legacyFallback;
import static org.neo4j.kernel.configuration.Settings.listenAddress;

public class ListenAddressSettingsTest
{
    private static Setting<ListenSocketAddress> legacy_address_setting = listenAddress( "address", 1234 );
    private static Setting<ListenSocketAddress> listen_address_setting =
            legacyFallback( legacy_address_setting, listenAddress( "listen_address", 1234 ) );

    @Test
    public void shouldParseExplicitSettingValueWhenProvided() throws Exception
    {
        // given
        Config config = Config.defaults();
        config.augment( stringMap( GraphDatabaseSettings.default_listen_address.name(), "server1.example.com" ) );
        config.augment( stringMap( listen_address_setting.name(), "server1.internal:4000" ) );

        // when
        ListenSocketAddress listenSocketAddress = config.get( listen_address_setting );

        // then
        assertEquals( "server1.internal", listenSocketAddress.getHostname() );
        assertEquals( 4000, listenSocketAddress.getPort() );
    }

    @Test
    public void shouldFallbackToLegacySettingWhenOnlyValueProvided() throws Exception
    {
        // given
        Config config = Config.defaults();
        config.augment( stringMap( GraphDatabaseSettings.default_listen_address.name(), "server1.example.com" ) );
        config.augment( stringMap( legacy_address_setting.name(), "server1.internal:4000" ) );

        // when
        ListenSocketAddress listenSocketAddress = config.get( listen_address_setting );

        // then
        assertEquals( "server1.internal", listenSocketAddress.getHostname() );
        assertEquals( 4000, listenSocketAddress.getPort() );
    }

    @Test
    public void shouldCombineDefaultHostnameWithSettingSpecificPortWhenNoValueProvided() throws Exception
    {
        // given
        Config config = Config.defaults();
        config.augment( stringMap( GraphDatabaseSettings.default_listen_address.name(), "server1.example.com" ) );

        // when
        ListenSocketAddress listenSocketAddress = config.get( listen_address_setting );

        // then
        assertEquals( "server1.example.com", listenSocketAddress.getHostname() );
        assertEquals( 1234, listenSocketAddress.getPort() );
    }

    @Test
    public void shouldCombineDefaultHostnameWithExplicitPortWhenOnlyAPortProvided() throws Exception
    {
        // given
        Config config = Config.defaults();
        config.augment( stringMap( GraphDatabaseSettings.default_listen_address.name(), "server1.example.com" ) );
        config.augment( stringMap( listen_address_setting.name(), ":4000" ) );

        // when
        ListenSocketAddress listenSocketAddress = config.get( listen_address_setting );

        // then
        assertEquals( "server1.example.com", listenSocketAddress.getHostname() );
        assertEquals( 4000, listenSocketAddress.getPort() );
    }
}
