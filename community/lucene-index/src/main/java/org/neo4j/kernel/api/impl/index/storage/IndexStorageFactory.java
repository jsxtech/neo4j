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
package org.neo4j.kernel.api.impl.index.storage;

import java.io.File;

import org.neo4j.io.fs.FileSystemAbstraction;

public class IndexStorageFactory
{
    private final DirectoryFactory dirFactory;
    private final FileSystemAbstraction fileSystem;
    private final File indexRootFolder;

    public IndexStorageFactory( DirectoryFactory dirFactory, FileSystemAbstraction fileSystem, File indexRootFolder )
    {
        this.dirFactory = dirFactory;
        this.fileSystem = fileSystem;
        this.indexRootFolder = indexRootFolder;
    }

    public PartitionedIndexStorage indexStorageOf( long indexId, boolean archiveFailed )
    {
        return new PartitionedIndexStorage( dirFactory, fileSystem, indexRootFolder, String.valueOf( indexId ), archiveFailed );
    }
}
