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
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.neo4j.kernel.impl.api.KernelTransactionsSnapshot;
import org.neo4j.kernel.impl.store.id.configuration.IdTypeConfiguration;
import org.neo4j.kernel.impl.store.id.configuration.IdTypeConfigurationProvider;

/**
 * Wraps {@link IdGenerator} for those that have {@link IdTypeConfiguration#allowAggressiveReuse() aggressive id reuse}
 * so that ids can be {@link IdGenerator#freeId(long) freed} at safe points in time, after all transactions
 * which were active at the time of freeing, have been closed.
 */
public class BufferingIdGeneratorFactory implements IdGeneratorFactory
{
    private final BufferingIdGenerator[/*IdType#ordinal as key*/] overriddenIdGenerators =
            new BufferingIdGenerator[IdType.values().length];
    private Supplier<KernelTransactionsSnapshot> boundaries;
    private Predicate<KernelTransactionsSnapshot> safeThreshold;
    private final IdGeneratorFactory delegate;
    private final IdTypeConfigurationProvider idTypeConfigurationProvider;

    public BufferingIdGeneratorFactory( IdGeneratorFactory delegate, Supplier<KernelTransactionsSnapshot> boundaries,
            IdReuseEligibility eligibleForReuse, IdTypeConfigurationProvider idTypeConfigurationProvider )
    {
        this.delegate = delegate;
        this.idTypeConfigurationProvider = idTypeConfigurationProvider;
        initialize( boundaries, eligibleForReuse );
    }

    private void initialize( Supplier<KernelTransactionsSnapshot> boundaries, IdReuseEligibility eligibleForReuse )
    {
        this.boundaries = boundaries;
        this.safeThreshold = snapshot -> snapshot.allClosed() && eligibleForReuse.isEligible( snapshot );
        for ( BufferingIdGenerator generator : overriddenIdGenerators )
        {
            if ( generator != null )
            {
                generator.initialize( boundaries, safeThreshold );
            }
        }
    }

    @Override
    public IdGenerator open( File filename, IdType idType, long highId, long maxId )
    {
        IdTypeConfiguration typeConfiguration = idTypeConfigurationProvider.getIdTypeConfiguration( idType );
        return open( filename, typeConfiguration.getGrabSize(), idType, highId, maxId);
    }

    @Override
    public IdGenerator open( File filename, int grabSize, IdType idType, long highId, long maxId )
    {
        IdGenerator generator = delegate.open( filename, grabSize, idType, highId, maxId );
        IdTypeConfiguration typeConfiguration = getIdTypeConfiguration(idType);
        if ( typeConfiguration.allowAggressiveReuse() )
        {
            BufferingIdGenerator bufferingGenerator = new BufferingIdGenerator( generator );

            // If shutdown was CLEAN
            // BufferingIdGeneratorFactory has lifecycle:
            //   - Construct
            //   - open (all store files)
            //   - initialize
            //
            // If Shutdown was UNCLEAN
            // BufferingIdGeneratorFactory has lifecycle:
            //   - Construct
            //   - open (all store files) will fail
            //   - initialize (with all generators being null)
            //   - recovery is performed
            //   - open (all store files) again
            //   - initialize will NOT be called again so...
            //   - call initialize on generators after open
            //   = that is why this if-statement is here
            if ( boundaries != null )
            {
                bufferingGenerator.initialize( boundaries, safeThreshold );
            }
            overriddenIdGenerators[idType.ordinal()] = bufferingGenerator;
            generator = bufferingGenerator;
        }
        return generator;
    }

    @Override
    public void create( File filename, long highId, boolean throwIfFileExists )
    {
        delegate.create( filename, highId, throwIfFileExists );
    }

    @Override
    public IdGenerator get( IdType idType )
    {
        IdGenerator generator = overriddenIdGenerators[idType.ordinal()];
        return generator != null ? generator : delegate.get( idType );
    }

    public void maintenance()
    {
        for ( BufferingIdGenerator generator : overriddenIdGenerators )
        {
            if ( generator != null )
            {
                generator.maintenance();
            }
        }
    }

    public void clear()
    {
        for ( BufferingIdGenerator generator : overriddenIdGenerators )
        {
            if ( generator != null )
            {
                generator.clear();
            }
        }
    }

    private IdTypeConfiguration getIdTypeConfiguration( IdType idType )
    {
        return idTypeConfigurationProvider.getIdTypeConfiguration( idType );
    }
}
