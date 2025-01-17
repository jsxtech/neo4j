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
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

import org.neo4j.io.pagecache.impl.ByteBufferPage;

/**
 * Utility for testing code that depends on page cursors.
 */
public class StubPageCursor extends PageCursor
{
    private long pageId;
    private int pageSize;
    protected ByteBufferPage page;
    private int currentOffset;
    private boolean observedOverflow;
    private String cursorErrorMessage;
    private boolean closed;
    private boolean needsRetry;
    protected StubPageCursor linkedCursor;

    public StubPageCursor( long initialPageId, int pageSize )
    {
        this.pageId = initialPageId;
        this.pageSize = pageSize;
        this.page = new ByteBufferPage( ByteBuffer.allocateDirect( pageSize ) );
    }

    public StubPageCursor( long initialPageId, ByteBuffer buffer )
    {
        this.pageId = initialPageId;
        this.pageSize = buffer.capacity();
        this.page = new ByteBufferPage( buffer );
    }

    @Override
    public long getCurrentPageId()
    {
        return pageId;
    }

    @Override
    public int getCurrentPageSize()
    {
        return pageSize;
    }

    @Override
    public File getCurrentFile()
    {
        return new File( "" );
    }

    @Override
    public void rewind()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean next() throws IOException
    {
        return true;
    }

    @Override
    public boolean next( long pageId ) throws IOException
    {
        return true;
    }

    @Override
    public void close()
    {
        closed = true;
        if ( linkedCursor != null )
        {
            linkedCursor.close();
            linkedCursor = null;
        }
    }

    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public boolean shouldRetry() throws IOException
    {
        if ( needsRetry )
        {
            checkAndClearBoundsFlag();
        }
        return needsRetry || (linkedCursor != null && linkedCursor.shouldRetry());
    }

    public void setNeedsRetry( boolean needsRetry )
    {
        this.needsRetry = needsRetry;
    }

    @Override
    public int copyTo( int sourceOffset, PageCursor targetCursor, int targetOffset, int lengthInBytes )
    {
        return 0;
    }

    @Override
    public boolean checkAndClearBoundsFlag()
    {
        boolean overflow = observedOverflow;
        observedOverflow = false;
        return overflow || (linkedCursor != null && linkedCursor.checkAndClearBoundsFlag());
    }

    @Override
    public void checkAndClearCursorException() throws CursorException
    {
        String message = this.cursorErrorMessage;
        if ( message != null )
        {
            throw new CursorException( message );
        }
    }

    @Override
    public void raiseOutOfBounds()
    {
        observedOverflow = true;
    }

    @Override
    public void setCursorException( String message )
    {
        this.cursorErrorMessage = message;
    }

    @Override
    public void clearCursorException()
    {
        this.cursorErrorMessage = null;
    }

    @Override
    public PageCursor openLinkedCursor( long pageId )
    {
        return linkedCursor = new StubPageCursor( pageId, pageSize );
    }

    @Override
    public byte getByte()
    {
        byte value = getByte( currentOffset );
        currentOffset += 1;
        return value;
    }

    @Override
    public byte getByte( int offset )
    {
        try
        {
            return page.getByte( offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            return handleOverflow();
        }
    }

    private byte handleOverflow()
    {
        observedOverflow = true;
        return (byte) ThreadLocalRandom.current().nextInt();
    }

    @Override
    public void putByte( byte value )
    {
        putByte( currentOffset, value );
        currentOffset += 1;
    }

    @Override
    public void putByte( int offset, byte value )
    {
        try
        {
            page.putByte( value, offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            handleOverflow();
        }
    }

    @Override
    public long getLong()
    {
        long value = getLong( currentOffset );
        currentOffset += 8;
        return value;
    }

    @Override
    public long getLong( int offset )
    {
        try
        {
            return page.getLong( offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            return handleOverflow();
        }
    }

    @Override
    public void putLong( long value )
    {
        putLong( currentOffset, value );
        currentOffset += 8;
    }

    @Override
    public void putLong( int offset, long value )
    {
        try
        {
            page.putLong( value, offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            handleOverflow();
        }
    }

    @Override
    public int getInt()
    {
        int value = getInt( currentOffset );
        currentOffset += 4;
        return value;
    }

    @Override
    public int getInt( int offset )
    {
        try
        {
            return page.getInt( offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            return handleOverflow();
        }
    }

    @Override
    public void putInt( int value )
    {
        putInt( currentOffset, value );
        currentOffset += 4;
    }

    @Override
    public void putInt( int offset, int value )
    {
        try
        {
            page.putInt( value, offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            handleOverflow();
        }
    }

    @Override
    public void getBytes( byte[] data )
    {
        getBytes( data, 0, data.length );
    }

    @Override
    public void getBytes( byte[] data, int arrayOffset, int length )
    {
        try
        {
            page.getBytes( data, currentOffset, arrayOffset, length );
            currentOffset += length;
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            handleOverflow();
        }
    }

    @Override
    public void putBytes( byte[] data )
    {
        putBytes( data, 0, data.length );
    }

    @Override
    public void putBytes( byte[] data, int arrayOffset, int length )
    {
        try
        {
            page.putBytes( data, currentOffset, arrayOffset, length );
            currentOffset += length;
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            handleOverflow();
        }
    }

    @Override
    public short getShort()
    {
        short value = getShort( currentOffset );
        currentOffset += 2;
        return value;
    }

    @Override
    public short getShort( int offset )
    {
        try
        {
            return page.getShort( offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            return handleOverflow();
        }
    }

    @Override
    public void putShort( short value )
    {
        putShort( currentOffset, value );
        currentOffset += 2;
    }

    @Override
    public void putShort( int offset, short value )
    {
        try
        {
            page.putShort( value, offset );
        }
        catch ( IndexOutOfBoundsException | BufferOverflowException | BufferUnderflowException e )
        {
            handleOverflow();
        }
    }

    @Override
    public int getOffset()
    {
        return currentOffset;
    }

    @Override
    public void setOffset( int offset )
    {
        if ( offset < 0 )
        {
            throw new IndexOutOfBoundsException();
        }
        currentOffset = offset;
    }

    public Page getPage()
    {
        return page;
    }

    @Override
    public String toString()
    {
        return "PageCursor{" +
               "currentOffset=" + currentOffset +
               ", page=" + page +
               '}';
    }
}
