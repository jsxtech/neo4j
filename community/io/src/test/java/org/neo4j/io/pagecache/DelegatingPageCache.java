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
package org.neo4j.io.pagecache;

import java.io.File;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.util.Optional;
import java.util.stream.Stream;

public class DelegatingPageCache implements PageCache
{
    private final PageCache delegate;

    public DelegatingPageCache( PageCache delegate )
    {
        this.delegate = delegate;
    }

    public PagedFile map( File file, int pageSize, OpenOption... openOptions ) throws IOException
    {
        return delegate.map( file, pageSize, openOptions );
    }

    @Override
    public Optional<PagedFile> getExistingMapping( File file ) throws IOException
    {
        return delegate.getExistingMapping( file );
    }

    public int pageSize()
    {
        return delegate.pageSize();
    }

    public void close()
    {
        delegate.close();
    }

    public int maxCachedPages()
    {
        return delegate.maxCachedPages();
    }

    @Override
    public Stream<FileHandle> streamFilesRecursive( File directory ) throws IOException
    {
        return delegate.streamFilesRecursive( directory );
    }

    public void flushAndForce( IOLimiter limiter ) throws IOException
    {
        delegate.flushAndForce( limiter );
    }

    public void flushAndForce() throws IOException
    {
        delegate.flushAndForce();
    }
}
