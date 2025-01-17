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
package org.neo4j.io.pagecache.impl;

import java.io.File;
import java.io.IOException;

public class FileIsMappedException extends IOException
{
    private final File file;
    private final Operation operation;

    public FileIsMappedException( File file, Operation operation )
    {
        super( operation.message + ": " + file );
        this.file = file;
        this.operation = operation;
    }

    public File getFile()
    {
        return file;
    }

    public Operation getOperation()
    {
        return operation;
    }

    public enum Operation
    {
        RENAME( "Cannot rename mapped file" ),
        DELETE( "Cannot delete mapped file" );

        private final String message;

        Operation( String message )
        {
            this.message = message;
        }
    }
}
