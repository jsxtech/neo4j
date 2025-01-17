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
package org.neo4j.kernel.impl.query;

import java.io.Closeable;
import java.io.File;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Service;
import org.neo4j.helpers.Strings;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.ExecutingQuery;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.extension.KernelExtensionFactory;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.spi.KernelContext;
import org.neo4j.kernel.impl.util.JobScheduler;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.logging.FormattedLog;
import org.neo4j.logging.Log;
import org.neo4j.logging.RotatingFileOutputStreamSupplier;
import org.neo4j.time.Clocks;

import static java.lang.String.format;
import static org.neo4j.io.file.Files.createOrOpenAsOuputStream;

@Service.Implementation( KernelExtensionFactory.class )
public class QueryLoggerKernelExtension extends KernelExtensionFactory<QueryLoggerKernelExtension.Dependencies>
{
    public interface Dependencies
    {
        FileSystemAbstraction fileSystem();

        Config config();

        Monitors monitoring();

        LogService logger();

        JobScheduler jobScheduler();
    }

    public QueryLoggerKernelExtension()
    {
        super( "query-logging" );
    }

    @Override
    public Lifecycle newInstance( @SuppressWarnings( "unused" ) KernelContext context,
            final Dependencies dependencies ) throws Throwable
    {
        final Config config = dependencies.config();
        boolean queryLogEnabled = config.get( GraphDatabaseSettings.log_queries );
        final File queryLogFile = config.get( GraphDatabaseSettings.log_queries_filename );
        final FileSystemAbstraction fileSystem = dependencies.fileSystem();
        final JobScheduler jobScheduler = dependencies.jobScheduler();
        final Monitors monitoring = dependencies.monitoring();

        if (!queryLogEnabled)
        {
            return createEmptyAdapter();
        }

        return new LifecycleAdapter()
        {
            Closeable closable;

            @Override
            public void init() throws Throwable
            {
                Long thresholdMillis = config.get( GraphDatabaseSettings.log_queries_threshold );
                Long rotationThreshold = config.get( GraphDatabaseSettings.log_queries_rotation_threshold );
                int maxArchives = config.get( GraphDatabaseSettings.log_queries_max_archives );
                boolean logQueryParameters = config.get( GraphDatabaseSettings.log_queries_parameter_logging_enabled );

                FormattedLog.Builder logBuilder = FormattedLog.withUTCTimeZone();
                Log log;
                if (rotationThreshold == 0)
                {
                    OutputStream logOutputStream = createOrOpenAsOuputStream( fileSystem, queryLogFile, true );
                    log = logBuilder.toOutputStream( logOutputStream );
                    closable = logOutputStream;
                }
                else
                {
                    RotatingFileOutputStreamSupplier
                            rotatingSupplier = new RotatingFileOutputStreamSupplier( fileSystem, queryLogFile,
                            rotationThreshold, 0, maxArchives,
                            jobScheduler.executor( JobScheduler.Groups.queryLogRotation ) );
                    log = logBuilder.toOutputStream( rotatingSupplier );
                    closable = rotatingSupplier;
                }

                QueryLogger logger = new QueryLogger( Clocks.systemClock(), log, thresholdMillis, logQueryParameters );
                monitoring.addMonitorListener( logger );
            }

            @Override
            public void shutdown() throws Throwable
            {
                closable.close();
            }
        };
    }

    private Lifecycle createEmptyAdapter()
    {
        return new LifecycleAdapter();
    }

    static class QueryLogger implements QueryExecutionMonitor
    {
        private final Clock clock;
        private final Log log;
        private final long thresholdMillis;
        private final boolean logQueryParameters;

        private static final Pattern PASSWORD_PATTERN = Pattern.compile(
                // call signature
                "(?:(?i)call)\\s+dbms(?:\\.security)?\\.change(?:User)?Password\\(" +
                // optional username parameter, in single, double quotes, or parametrized
                "(?:\\s*(?:'(?:(?<=\\\\)'|[^'])*'|\"(?:(?<=\\\\)\"|[^\"])*\"|[^,]*)\\s*,)?" +
                // password parameter, in single, double quotes, or parametrized
                "\\s*('(?:(?<=\\\\)'|[^'])*'|\"(?:(?<=\\\\)\"|[^\"])*\"|\\$\\w*|\\{\\w*\\})\\s*\\)" );

        QueryLogger( Clock clock, Log log, long thresholdMillis, boolean logQueryParameters )
        {
            this.clock = clock;
            this.log = log;
            this.thresholdMillis = thresholdMillis;
            this.logQueryParameters = logQueryParameters;
        }

        @Override
        public void startQueryExecution( ExecutingQuery query )
        {
        }

        @Override
        public void endFailure( ExecutingQuery query, Throwable failure )
        {
            long time = clock.millis() - query.startTime();
            log.error( logEntry( time, query ), failure );
        }

        @Override
        public void endSuccess( ExecutingQuery query )
        {
            long time = clock.millis() - query.startTime();
            if ( time >= thresholdMillis )
            {
                log.info( logEntry( time, query ) );
            }
        }

        private String logEntry( long time, ExecutingQuery query )
        {
            String sourceString = query.querySource().toString();
            String queryText = query.queryText();
            String metaData = mapAsString( query.metaData() );

            Set<String> passwordParams = new HashSet<>();
            Matcher matcher = PASSWORD_PATTERN.matcher( queryText );

            while ( matcher.find() )
            {
                String password = matcher.group( 1 ).trim();
                if ( password.charAt( 0 ) == '$' )
                {
                    passwordParams.add( password.substring( 1 ) );
                }
                else if ( password.charAt( 0 ) == '{' )
                {
                    passwordParams.add( password.substring( 1, password.length() - 1 ) );
                }
                else
                {
                    queryText = queryText.replace( password, "******" );
                    password = "";
                }
            }

            if ( logQueryParameters )
            {
                String params = mapAsString( query.queryParameters(), passwordParams );
                return format( "%d ms: %s - %s - %s - %s", time, sourceString, queryText, params, metaData );
            }
            else
            {
                return format( "%d ms: %s - %s - %s", time, sourceString, queryText, metaData );
            }
        }

        private static String mapAsString( Map<String,Object> params )
        {
            return mapAsString( params, Collections.emptySet() );
        }

        private static String mapAsString( Map<String, Object> params, Collection<String> obfuscate )
        {
            if ( params == null )
            {
                return "{}";
            }

            StringBuilder builder = new StringBuilder( "{" );
            String sep = "";
            for ( Map.Entry<String,Object> entry : params.entrySet() )
            {
                builder
                        .append( sep )
                        .append( entry.getKey() )
                        .append( ": " );

                if ( obfuscate.contains( entry.getKey() ) )
                {
                    builder.append( "******" );
                }
                else
                {
                    builder.append( valueToString( entry.getValue() ) );
                }
                sep = ", ";
            }
            builder.append( "}" );

            return builder.toString();
        }

        private static String valueToString( Object value )
        {
            if ( value instanceof Map<?,?> )
            {
                return mapAsString( (Map<String,Object>) value );
            }
            if ( value instanceof String )
            {
                return format( "'%s'", String.valueOf( value ) );
            }
            return Strings.prettyPrint( value );
        }
    }
}
