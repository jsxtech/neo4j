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
package org.neo4j.server.configuration;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.neo4j.bolt.BoltKernelExtension;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.Description;
import org.neo4j.kernel.configuration.Internal;
import org.neo4j.kernel.configuration.Settings;
import org.neo4j.server.web.JettyThreadCalculator;

import static org.neo4j.kernel.configuration.Settings.BOOLEAN;
import static org.neo4j.kernel.configuration.Settings.BYTES;
import static org.neo4j.kernel.configuration.Settings.DURATION;
import static org.neo4j.kernel.configuration.Settings.EMPTY;
import static org.neo4j.kernel.configuration.Settings.FALSE;
import static org.neo4j.kernel.configuration.Settings.INTEGER;
import static org.neo4j.kernel.configuration.Settings.NORMALIZED_RELATIVE_URI;
import static org.neo4j.kernel.configuration.Settings.NO_DEFAULT;
import static org.neo4j.kernel.configuration.Settings.STRING;
import static org.neo4j.kernel.configuration.Settings.STRING_LIST;
import static org.neo4j.kernel.configuration.Settings.TRUE;
import static org.neo4j.kernel.configuration.Settings.max;
import static org.neo4j.kernel.configuration.Settings.min;
import static org.neo4j.kernel.configuration.Settings.pathSetting;
import static org.neo4j.kernel.configuration.Settings.range;
import static org.neo4j.kernel.configuration.Settings.setting;

@Description("Settings used by the server configuration")
public interface ServerSettings
{
    @Description("Maximum request header size")
    @Internal
    Setting<Integer> maximum_request_header_size =
            setting( "unsupported.dbms.max_http_request_header_size", INTEGER, "20480" );

    @Description("Maximum response header size")
    @Internal
    Setting<Integer> maximum_response_header_size =
            setting( "unsupported.dbms.max_http_response_header_size", INTEGER, "20480" );

    @Description("Comma-seperated list of custom security rules for Neo4j to use.")
    Setting<List<String>> security_rules = setting( "dbms.security.http_authorization_classes", STRING_LIST, EMPTY );

    @Description( "Number of Neo4j worker threads, your OS might enforce a lower limit than the maximum value " +
            "specified here." )
    Setting<Integer> webserver_max_threads = setting( "dbms.threads.worker_count", INTEGER,
            "" + Math.min( Runtime.getRuntime().availableProcessors(), 500 ),
            range( 1, JettyThreadCalculator.MAX_THREADS ) );

    @Description( "If execution time limiting is enabled in the database, this configures the maximum request execution time. " +
            "Please use dbms.transaction.timeout instead." )
    @Internal
    @Deprecated
    Setting<Long> webserver_limit_execution_time = setting( "unsupported.dbms.executiontime_limit.time", DURATION, NO_DEFAULT );

    @Internal
    Setting<List<String>> console_module_engines = setting(
            "unsupported.dbms.console_module.engines", STRING_LIST, "SHELL" );

    @Description("Comma-separated list of <classname>=<mount point> for unmanaged extensions.")
    Setting<List<ThirdPartyJaxRsPackage>> third_party_packages = setting( "dbms.unmanaged_extension_classes",
            new Function<String, List<ThirdPartyJaxRsPackage>>()
            {
                @Override
                public List<ThirdPartyJaxRsPackage> apply( String value )
                {
                    String[] list = value.split( Settings.SEPARATOR );
                    List<ThirdPartyJaxRsPackage> result = new ArrayList<>();
                    for ( String item : list )
                    {
                        item = item.trim();
                        if ( !item.equals( "" ) )
                        {
                            result.add( createThirdPartyJaxRsPackage( item ) );
                        }
                    }
                    return result;
                }

                @Override
                public String toString()
                {
                    return "a comma-seperated list of <classname>=<mount point> strings";
                }

                private ThirdPartyJaxRsPackage createThirdPartyJaxRsPackage( String packageAndMoutpoint )
                {
                    String[] parts = packageAndMoutpoint.split( "=" );
                    if ( parts.length != 2 )
                    {
                        throw new IllegalArgumentException( "config for " + ServerSettings.third_party_packages.name()
                                + " is wrong: " + packageAndMoutpoint );
                    }
                    String pkg = parts[0];
                    String mountPoint = parts[1];
                    return new ThirdPartyJaxRsPackage( pkg, mountPoint );
                }
            },
            EMPTY );

    @Description( "Directory for storing certificates to be used by Neo4j for TLS connections. Certificate files must be named _neo4j.cert_ and _neo4j.key_" )
    Setting<File> certificates_directory = BoltKernelExtension.Settings.certificates_directory;

    @Internal
    @Description("Path to the X.509 public certificate to be used by Neo4j for TLS connections")
    Setting<File> tls_certificate_file = BoltKernelExtension.Settings.tls_certificate_file;

    @Internal
    @Description("Path to the X.509 private key to be used by Neo4j for TLS connections")
    Setting<File> tls_key_file = BoltKernelExtension.Settings.tls_key_file;

    @Description("Enable HTTP request logging.")
    Setting<Boolean> http_logging_enabled = setting( "dbms.logs.http.enabled", BOOLEAN, FALSE );

    @Description("Number of HTTP logs to keep.")
    Setting<Integer> http_logging_rotation_keep_number = setting("dbms.logs.http.rotation.keep_number", INTEGER, "5");

    @Description("Size of each HTTP log that is kept.")
    Setting<Long> http_logging_rotation_size = setting("dbms.logs.http.rotation.size", BYTES, "20m", min(0L), max( Long.MAX_VALUE ) );

    @SuppressWarnings("unused") // used only in the startup scripts
    @Description("Enable GC Logging")
    Setting<Boolean> gc_logging_enabled = setting("dbms.logs.gc.enabled", BOOLEAN, FALSE);

    @SuppressWarnings("unused") // used only in the startup scripts
    @Description("GC Logging Options")
    Setting<String> gc_logging_options = setting("dbms.logs.gc.options", STRING, "" +
            "-XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime " +
            "-XX:+PrintPromotionFailure -XX:+PrintTenuringDistribution");

    @SuppressWarnings("unused") // used only in the startup scripts
    @Description("Number of GC logs to keep.")
    Setting<Integer> gc_logging_rotation_keep_number = setting("dbms.logs.gc.rotation.keep_number", INTEGER, "5");

    @SuppressWarnings("unused") // used only in the startup scripts
    @Description("Size of each GC log that is kept.")
    Setting<Long> gc_logging_rotation_size = setting("dbms.logs.gc.rotation.size", BYTES, "20m", min(0L), max( Long.MAX_VALUE ) );

    @SuppressWarnings("unused") // used only in the startup scripts
    @Description("Path of the run directory. This directory holds Neo4j's runtime state, such as a pidfile when it is" +
            " running in the background. The pidfile is created when starting neo4j and removed when stopping it." +
            " It may be placed on an in-memory filesystem such as tmpfs.")
    Setting<File> run_directory = pathSetting( "dbms.directories.run", "run" );

    @SuppressWarnings("unused") // used only in the startup scripts
    @Description("Path of the lib directory")
    Setting<File> lib_directory = pathSetting( "dbms.directories.lib", "lib" );

    @Description("Timeout for idle transactions in the REST endpoint.")
    Setting<Long> transaction_idle_timeout = setting( "dbms.rest.transaction.idle_timeout", DURATION, "60s" );

    @Internal
    Setting<URI> rest_api_path = setting( "unsupported.dbms.uris.rest", NORMALIZED_RELATIVE_URI, "/db/data" );

    @Internal
    Setting<URI> management_api_path = setting( "unsupported.dbms.uris.management",
            NORMALIZED_RELATIVE_URI, "/db/manage" );

    @Internal
    Setting<URI> browser_path = setting( "unsupported.dbms.uris.browser", Settings.URI, "/browser/" );

    @Internal
    Setting<Boolean> script_sandboxing_enabled = setting("unsupported.dbms.security.script_sandboxing_enabled",
            BOOLEAN, TRUE );

    @Internal
    Setting<Boolean> wadl_enabled = setting( "unsupported.dbms.wadl_generation_enabled", BOOLEAN,
            FALSE );

    @Internal
    Setting<Boolean> console_module_enabled = setting( "unsupported.dbms.console_module.enabled", BOOLEAN, TRUE );
}
