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
package org.neo4j.commandline.admin;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.neo4j.commandline.arguments.Arguments;
import org.neo4j.helpers.Service;
import org.neo4j.helpers.collection.Iterables;

/**
 * To create a command for {@code neo4j-admin}:
 * <ol>
 *   <li>implement {@code AdminCommand}</li>
 *   <li>create a concrete subclass of {@code AdminCommand.Provider} which instantiates the command</li>
 *   <li>register the {@code Provider} in {@code META-INF/services} as described
 *     <a href='https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html'>here</a></li>
 * </ol>
 */
public interface AdminCommand
{
    abstract class Provider extends Service
    {
        /**
         * Create a new instance of a service implementation identified with the
         * specified key(s).
         *
         * @param key     the main key for identifying this service implementation
         * @param altKeys alternative spellings of the identifier of this service
         */
        protected Provider( String key, String... altKeys )
        {
            super( key, altKeys );
        }

        /**
         * @return The command's name
         */
        public String name()
        {
            return Iterables.last( getKeys() );
        }

        /**
         * @return The arguments this command accepts.
         */
        public abstract Arguments allArguments();

        /**
         *
         * @return A list of possibly mutually-exclusive argument sets for this command.
         */
        public List<Arguments> possibleArguments()
        {
            return Arrays.asList( allArguments() );
        }

        /**
         * @return A single-line summary for the command. Should be 70 characters or less.
         */
        public abstract String summary();

        /**
         * @return A description for the command's help text.
         */
        public abstract String description();

        public abstract AdminCommand create( Path homeDir, Path configDir, OutsideWorld outsideWorld );
    }

    interface Blocker
    {
        /**
         * @param homeDir   the home of the Neo4j installation.
         * @param configDir the directory where configuration files can be found.
         * @return A boolean representing whether or not this command should be blocked from running.
         */
        boolean doesBlock( Path homeDir, Path configDir );

        /**
         * @return A list of the commands this blocker applies to.
         */
        Set<String> commands();

        /**
         * @return An explanation of why a command was blocked. This will be shown to the user.
         */
        String explanation();
    }

    void execute( String[] args ) throws IncorrectUsage, CommandFailed;
}
