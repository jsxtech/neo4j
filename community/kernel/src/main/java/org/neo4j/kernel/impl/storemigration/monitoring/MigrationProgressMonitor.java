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
package org.neo4j.kernel.impl.storemigration.monitoring;

public interface MigrationProgressMonitor
{
    /**
     * Signals that the migration process has started.
     */
    void started();

    /**
     * Signals that migration goes into section with given {@code name}.
     *
     * @param name descriptive name of the section to migration.
     * @return {@link Section} which should be notified about progress in the given section.
     */
    Section startSection( String name );

    /**
     * The migration process has completed successfully.
     */
    void completed();

    interface Section
    {
        /**
         * @param max max progress, which {@link #progress(long)} moves towards.
         */
        void start( long max );

        /**
         * Percentage completeness for the current section.
         *
         * @param add progress to add towards a maximum.
         */
        void progress( long add );

        /**
         * Called if this section was completed successfully.
         */
        void completed();
    }
}
