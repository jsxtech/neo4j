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

import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;

public class SingleFilePageSwapperWithRealFileSystemIT extends SingleFilePageSwapperTest
{
    private final DefaultFileSystemAbstraction fs = new DefaultFileSystemAbstraction();

    @Override
    public void tearDown()
    {
        // Do nothing
    }

    @Override
    protected File getFile()
    {
        return testDir.file( super.getFile().getName() );
    }

    @Override
    protected FileSystemAbstraction getFs()
    {
        return fs;
    }
}
