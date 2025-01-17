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
package org.neo4j.kernel.impl.store.id;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.impl.store.InvalidIdGeneratorException;
import org.neo4j.kernel.impl.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.id.validation.IdValidator;

import static java.lang.Math.max;

/**
 * This class generates unique ids for a resource type. For example, nodes in a
 * nodes space are connected to each other via relationships. On nodes and
 * relationship one can add properties. We have three different resource types
 * here (nodes, relationships and properties) where each resource needs a unique
 * id to be able to differ resources of the same type from each other. Creating
 * three id generators (one for each resource type ) will do the trick.
 * <p>
 * <CODE>IdGenerator</CODE> makes use of so called "defragged" ids. A
 * defragged id is an id that has been in use one or many times but the resource
 * that was using it doesn't exist anymore. This makes it possible to reuse the
 * id and that in turn makes it possible to write a resource store with fixed
 * records and size (you can calculate the position of a record by knowing the
 * id without using indexes or a translation table).
 * <p>
 * The id returned from {@link #nextId} may not be the lowest
 * available id but will be one of the defragged ids if such exist or the next
 * new free id that has never been used.
 * <p>
 * The {@link #freeId} will not check if the id passed in to it really is free.
 * Passing a non free id will corrupt the id generator and {@link #nextId}
 * method will eventually return that id.
 * <p>
 * The {@link #close()} method must always be invoked when done using an
 * generator (for this time). Failure to do will render the generator as
 * "sticky" and unusable next time you try to initialize a generator using the
 * same file. There can only be one id generator instance per id generator file.
 * <p>
 * In case of disk/file I/O failure an <CODE>IOException</CODE> is thrown.
 */
public class IdGeneratorImpl implements IdGenerator
{
    // sticky(byte), nextFreeId(long)
    public static final int HEADER_SIZE = 9;

    // if sticky the id generator wasn't closed properly so it has to be
    // rebuilt (go through the node, relationship, property, rel type etc files)
    private static final byte CLEAN_GENERATOR = (byte) 0;
    private static final byte STICKY_GENERATOR = (byte) 1;

    /**
     * Invalid and reserved id value. Represents special values, f.ex. the end of a relationships/property chain.
     * Please use {@link IdValidator} to validate generated ids.
     */
    public static final long INTEGER_MINUS_ONE = 0xFFFFFFFFL;  // 4294967295L;

    // number of defragged ids to grab from file in batch (also used for write)
    private int grabSize = -1;
    private final AtomicLong highId = new AtomicLong( -1 );

    private final File file;
    private final FileSystemAbstraction fs;
    private StoreChannel fileChannel = null;

    private final long max;
    private final boolean aggressiveReuse;
    private FreeIdKeeper keeper;

    /**
     * Opens the id generator represented by <CODE>fileName</CODE>. The
     * <CODE>grabSize</CODE> means how many defragged ids we should keep in
     * memory and is also the size (x4) of the two buffers used for reading and
     * writing to the id generator file. The highest returned id will be read
     * from file and if <CODE>grabSize</CODE> number of ids exist they will be
     * read into memory (if less exist all defragged ids will be in memory).
     * <p>
     * If this id generator hasn't been closed properly since the previous
     * session (sticky) an <CODE>IOException</CODE> will be thrown. When this
     * happens one has to rebuild the id generator from the (node/rel/prop)
     * store file.
     *
     * @param file
     *            The file name (and path if needed) for the id generator to be
     *            opened
     * @param grabSize
     *            The number of defragged ids to keep in memory
     * @param max is the highest possible id to be returned by this id generator from
     * {@link #nextId()}.
     * @param aggressiveReuse will reuse ids during the same session, not requiring
     * a restart to be able reuse ids freed with {@link #freeId(long)}.
     * @param highId the highest id in use.
     * @throws UnderlyingStorageException
     *             If no such file exist or if the id generator is sticky
     */
    public IdGeneratorImpl( FileSystemAbstraction fs, File file, int grabSize, long max, boolean aggressiveReuse,
            long highId )
    {
        this.fs = fs;
        this.aggressiveReuse = aggressiveReuse;
        if ( grabSize < 1 )
        {
            throw new IllegalArgumentException( "Illegal grabSize: " + grabSize );
        }
        this.max = max;
        this.file = file;
        this.grabSize = grabSize;
        initGenerator();
        this.highId.set( max( this.highId.get(), highId ) );
    }

    /**
     * Returns the next "free" id. If a defragged id exist it will be returned
     * else the next free id that hasn't been used yet is returned. If no id
     * exist the capacity is exceeded (all values <= max are taken) and a
     * {@link UnderlyingStorageException} will be thrown.
     *
     * @return The next free id
     * @throws UnderlyingStorageException
     *             If the capacity is exceeded
     * @throws IllegalStateException if this id generator has been closed
     */
    @Override
    public synchronized long nextId()
    {
        assertStillOpen();
        long nextDefragId = keeper.getId();
        if ( nextDefragId != -1 )
        {
            return nextDefragId;
        }

        long id = highId.get();
        if ( IdValidator.isReservedId( id ) )
        {
            id = highId.incrementAndGet();
        }
        IdValidator.assertValidId( id, max );
        highId.incrementAndGet();
        return id;
    }

    private void assertStillOpen()
    {
        if ( fileChannel == null )
        {
            throw new IllegalStateException( "Closed id generator " + file );
        }
    }

    @Override
    public synchronized IdRange nextIdBatch( int size )
    {
        assertStillOpen();

        // Get from defrag list
        int count = 0;
        long[] defragIds = new long[size];
        while ( count < size )
        {
            long id = keeper.getId();
            if ( id == -1 )
            {
                break;
            }
            defragIds[count++] = id;
        }

        // Shrink the array to actual size
        long[] tmpArray = defragIds;
        defragIds = new long[count];
        System.arraycopy( tmpArray, 0, defragIds, 0, count );

        int sizeLeftForRange = size - count;
        long start = highId.get();
        setHighId( start + sizeLeftForRange );
        return new IdRange( defragIds, start, sizeLeftForRange );
    }

    /**
     * Sets the next free "high" id. This method should be called when an id
     * generator has been rebuilt. {@code id} must not be higher than {@code max}.
     *
     * @param id The next free id returned from {@link #nextId()} if there are no existing free ids.
     */
    @Override
    public void setHighId( long id )
    {
        IdValidator.assertIdWithinCapacity( id, max );
        highId.set( id );
    }

    /**
     * Returns the next "high" id that will be returned if no defragged ids
     * exist.
     *
     * @return The next free "high" id
     */
    @Override
    public long getHighId()
    {
        return highId.get();
    }

    @Override
    public long getHighestPossibleIdInUse()
    {
        return getHighId()-1;
    }

    /**
     * Frees the <CODE>id</CODE> making it a defragged id that will be
     * returned by next id before any new id (that hasn't been used yet) is
     * returned.
     * <p>
     * This method will throw an <CODE>IOException</CODE> if id is negative or
     * if id is greater than the highest returned id. However as stated in the
     * class documentation above the id isn't validated to see if it really is
     * free.
     *
     * @param id
     *            The id to be made available again
     */
    @Override
    public synchronized void freeId( long id )
    {
        if ( IdValidator.isReservedId( id ) )
        {
            return;
        }

        if ( fileChannel == null )
        {
            throw new IllegalStateException( "Generator closed " + file );
        }
        if ( id < 0 || id >= highId.get() )
        {
            throw new IllegalArgumentException( "Illegal id[" + id + "], highId is " + highId.get() );
        }
        keeper.freeId( id );
    }

    /**
     * Closes the id generator flushing defragged ids in memory to file. The
     * file will be truncated to the minimal size required to hold all defragged
     * ids and it will be marked as clean (not sticky).
     * <p>
     * An invoke to the <CODE>nextId</CODE> or <CODE>freeId</CODE> after
     * this method has been invoked will result in an <CODE>IOException</CODE>
     * since the highest returned id has been set to a negative value.
     */
    @Override
    public synchronized void close()
    {
        if ( isClosed() )
        {
            return;
        }

        try
        {
            keeper.close(); // first write out free ids, then mark as clean
            ByteBuffer buffer = ByteBuffer.allocate( HEADER_SIZE );
            writeHeader( buffer );
            fileChannel.force( false );

            markAsCleanlyClosed( buffer );

            closeChannel();
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException(
                    "Unable to close id generator " + file, e );
        }
    }

    private boolean isClosed()
    {
        return highId.get() == -1;
    }

    private void closeChannel() throws IOException
    {
        // flush and close
        fileChannel.force( false );
        fileChannel.close();
        fileChannel = null;
        // make this generator unusable
        highId.set( -1 );
    }

    private void markAsCleanlyClosed( ByteBuffer buffer ) throws IOException
    {
        // remove sticky
        buffer.clear();
        buffer.put( CLEAN_GENERATOR );
        buffer.limit( 1 );
        buffer.flip();
        fileChannel.position( 0 );
        fileChannel.write( buffer );
    }

    private void writeHeader( ByteBuffer buffer ) throws IOException
    {
        fileChannel.position( 0 );
        buffer.put( STICKY_GENERATOR ).putLong( highId.get() );
        buffer.flip();
        fileChannel.write( buffer );
    }

    /**
     * Creates a new id generator.
     *
     * @param fileName The name of the id generator
     * @param throwIfFileExists if {@code true} will cause an {@link UnderlyingStorageException} to be thrown if
     * the file already exists. if {@code false} will truncate the file writing the header in it.
     */
    public static void createGenerator( FileSystemAbstraction fs, File fileName, long highId,
                                        boolean throwIfFileExists )
    {
        // sanity checks
        if ( fs == null )
        {
            throw new IllegalArgumentException( "Null filesystem" );
        }
        if ( fileName == null )
        {
            throw new IllegalArgumentException( "Null filename" );
        }
        if ( throwIfFileExists && fs.fileExists( fileName ) )
        {
            throw new IllegalStateException( "Can't create IdGeneratorFile["
                + fileName + "], file already exists" );
        }
        try ( StoreChannel channel = fs.create( fileName ) )
        {
            // write the header
            channel.truncate( 0 );
            ByteBuffer buffer = ByteBuffer.allocate( HEADER_SIZE );
            buffer.put( CLEAN_GENERATOR ).putLong( highId ).flip();
            channel.write( buffer );
            channel.force( false );
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException(
                "Unable to create id generator" + fileName, e );
        }
    }

    // initialize the id generator and performs a simple validation
    private synchronized void initGenerator()
    {
        try
        {
            fileChannel = fs.open( file, "rw" );
            ByteBuffer buffer = readHeader();
            markAsSticky( fileChannel, buffer );

            fileChannel.position( HEADER_SIZE );
            this.keeper = new FreeIdKeeper( fileChannel, grabSize, aggressiveReuse );
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException(
                    "Unable to init id generator " + file, e );
        }
    }

    /**
     * Made available for testing purposes.
     * Marks an id generator as sticky, i.e. not cleanly shut down.
     */
    public static void markAsSticky( StoreChannel fileChannel, ByteBuffer buffer ) throws IOException
    {
        buffer.clear();
        buffer.put( STICKY_GENERATOR ).limit( 1 ).flip();
        fileChannel.position( 0 );
        fileChannel.write( buffer );
        fileChannel.force( false );
    }

    private ByteBuffer readHeader() throws IOException
    {
        try
        {
            ByteBuffer buffer = readHighIdFromHeader( fileChannel, file );
            this.highId.set( buffer.getLong() );
            return buffer;
        }
        catch ( InvalidIdGeneratorException e )
        {
            fileChannel.close();
            throw e;
        }
    }

    private static ByteBuffer readHighIdFromHeader( StoreChannel channel, File fileName ) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate( HEADER_SIZE );
        int read = channel.read( buffer );
        if ( read != HEADER_SIZE )
        {
            throw new InvalidIdGeneratorException(
                "Unable to read header, bytes read: " + read );
        }
        buffer.flip();
        byte storageStatus = buffer.get();
        if ( storageStatus != CLEAN_GENERATOR )
        {
            throw new InvalidIdGeneratorException( "Sticky generator[ " +
                fileName + "] delete this id file and build a new one" );
        }
        return buffer;
    }

    public static long readHighId( FileSystemAbstraction fileSystem, File file ) throws IOException
    {
        try ( StoreChannel channel = fileSystem.open( file, "r" ) )
        {
            return readHighIdFromHeader( channel, file ).getLong();
        }
    }

    public synchronized void dumpFreeIds() throws IOException
    {
        keeper.dumpFreeIds();
        System.out.println( "\nNext free id: " + highId );
        close();
    }

    @Override
    public synchronized long getNumberOfIdsInUse()
    {
        return highId.get() - keeper.getCount();
    }

    @Override
    public long getDefragCount()
    {
        return keeper.getCount();
    }

    @Override
    public synchronized void delete()
    {
        if ( !isClosed() )
        {
            try
            {
                closeChannel();
            }
            catch ( IOException e )
            {
                throw new UnderlyingStorageException( "Unable to safe close id generator " + file, e );
            }
        }

        if ( !fs.deleteFile( file ) )
        {
            throw new UnderlyingStorageException( "Unable to delete id generator " + file );
        }
    }

    @Override
    public String toString()
    {
        return "IdGeneratorImpl " + hashCode() + " [highId=" + highId + ", defragged=" + keeper.getCount() + ", fileName="
               + file + ", max=" + max + ", aggressive=" + aggressiveReuse + "]";
    }
}
