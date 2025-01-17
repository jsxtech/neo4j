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
package org.neo4j.kernel.api.impl.index.builder;

import java.io.File;

import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.impl.index.storage.PartitionedIndexStorage;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.factory.OperationalMode;

/**
 * Base class for lucene index builders.
 *
 * @param <T> actual index type
 */
public abstract class AbstractLuceneIndexBuilder<T extends AbstractLuceneIndexBuilder<T>>
{
    protected LuceneIndexStorageBuilder storageBuilder = LuceneIndexStorageBuilder.create();
    private Config config = Config.defaults();
    private OperationalMode operationalMode = OperationalMode.single;

    /**
     * Specify index storage
     *
     * @param indexStorage index storage
     * @return index builder
     */
    public T withIndexStorage( PartitionedIndexStorage indexStorage )
    {
        storageBuilder.withIndexStorage( indexStorage );
        return (T) this;
    }

    /**
     * Specify directory factory
     *
     * @param directoryFactory directory factory
     * @return index builder
     */
    public T withDirectoryFactory( DirectoryFactory directoryFactory )
    {
        storageBuilder.withDirectoryFactory( directoryFactory );
        return (T) this;
    }

    /**
     * Specify file system abstraction
     *
     * @param fileSystem file system abstraction
     * @return index builder
     */
    public T withFileSystem( FileSystemAbstraction fileSystem )
    {
        storageBuilder.withFileSystem( fileSystem );
        return (T) this;
    }

    /**
     * Specify index root folder
     *
     * @param indexRootFolder root folder
     * @return index builder
     */
    public T withIndexRootFolder( File indexRootFolder )
    {
        storageBuilder.withIndexRootFolder( indexRootFolder );
        return (T) this;
    }

    /**
     * Specify index identifier
     *
     * @param indexIdentifier identifier
     * @return index builder
     */
    public T withIndexIdentifier( String indexIdentifier )
    {
        storageBuilder.withIndexIdentifier( indexIdentifier );
        return (T) this;
    }

    /**
     * Specify db config
     * @param config configuration
     * @return index builder
     */
    public T withConfig( Config config )
    {
        this.config = config;
        return (T) this;
    }

    /**
     * Specify db operational mode
     * @param operationalMode operational mode
     * @return index builder
     */
    public T withOperationalMode( OperationalMode operationalMode )
    {
        this.operationalMode = operationalMode;
        return (T) this;
    }

    /**
     * Check if index should be read only
     * @return true if index should be read only
     */
    protected boolean isReadOnly()
    {
        return getConfig( GraphDatabaseSettings.read_only ) && (OperationalMode.single == operationalMode);
    }

    /**
     * Lookup a config parameter.
     * @param flag the parameter to look up.
     * @param <F> the type of the parameter.
     * @return the value of the parameter.
     */
    protected  <F> F getConfig( Setting<F> flag )
    {
        return config.get( flag );
    }
}
