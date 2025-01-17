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
package org.neo4j.causalclustering.catchup.storecopy;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

import org.neo4j.io.fs.FileSystemAbstraction;

public class StoreFiles
{
    private static final FilenameFilter STORE_FILE_FILTER = ( dir, name ) ->
    {
        // Skip log files and tx files from temporary database
        return !name.startsWith( "metrics" ) && !name.startsWith( "temp-copy" ) &&
                !name.startsWith( "raft-messages." ) && !name.startsWith( "debug." ) &&
                !name.startsWith( "data" ) && !name.startsWith( "store_lock" );
    };
    private FileSystemAbstraction fs;

    public StoreFiles( FileSystemAbstraction fs )
    {
        this.fs = fs;
    }

    public void delete( File storeDir ) throws IOException
    {
        for ( File file : fs.listFiles( storeDir, STORE_FILE_FILTER ) )
        {
            fs.deleteRecursively( file );
        }

    }

    void moveTo( File source, File target ) throws IOException
    {
        for ( File candidate : fs.listFiles( source, STORE_FILE_FILTER ) )
        {
            fs.moveToDirectory( candidate, target );
        }
    }
}
