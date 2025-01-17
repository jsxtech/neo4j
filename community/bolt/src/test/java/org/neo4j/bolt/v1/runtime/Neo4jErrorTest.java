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
package org.neo4j.bolt.v1.runtime;

import org.junit.Test;

import org.neo4j.cypher.LoadExternalResourceException;
import org.neo4j.graphdb.DatabaseShutdownException;
import org.neo4j.kernel.DeadlockDetectedException;
import org.neo4j.kernel.api.exceptions.Status;

import static java.util.Arrays.asList;
import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class Neo4jErrorTest
{
    @Test
    public void shouldAssignUnknownStatusToUnpredictedException()
    {
        // Given
        Throwable cause = new Throwable( "This is not an error we know how to handle." );
        Neo4jError error = Neo4jError.from( cause );

        // Then
        assertThat( error.status(), equalTo( Status.General.UnknownError ) );
    }

    @Test
    public void shouldConvertDeadlockException() throws Throwable
    {
        // When
        Neo4jError error = Neo4jError.from( new DeadlockDetectedException( null ) );

        // Then
        assertEquals( error.status(), Status.Transaction.DeadlockDetected );
    }

    @Test
    public void loadExternalResourceShouldNotReferToLog()
    {
        // Given
        Neo4jError error = Neo4jError.from( new LoadExternalResourceException( "foo", null ) );

        // Then
        assertThat( error.status().code().classification().shouldLog(), is( false ) );
    }

    @Test
    public void shouldSetStatusToDatabaseUnavailableOnDatabaseShutdownException()
    {
        // Given
        DatabaseShutdownException ex = new DatabaseShutdownException();

        // When
        Neo4jError error = Neo4jError.from( ex );

        // Then
        assertThat( error.status(), equalTo( Status.General.DatabaseUnavailable ) );
        assertThat( error.cause(), equalTo( ex ) );
    }

    @Test
    public void shouldCombineErrors()
    {
       // Given
        Neo4jError error1 = Neo4jError.from( new DeadlockDetectedException( "In my life" ) );
        Neo4jError error2 = Neo4jError.from( new DeadlockDetectedException( "Why do I give valuable time" ) );
        Neo4jError error3 = Neo4jError.from( new DeadlockDetectedException( "To people who don't care if I live or die?" ) );

        // When
        Neo4jError combine = Neo4jError.combine( asList( error1, error2, error3 ) );

        // Then
        assertThat( combine.status(), equalTo( Status.Transaction.DeadlockDetected ) );
        assertThat( combine.message(), equalTo( String.format(
                "The following errors has occurred:%n%n" +
                "In my life%n" +
                "Why do I give valuable time%n" +
                "To people who don't care if I live or die?"
        )));
    }

    @Test
    public void shouldBeUnknownIfCombiningDifferentStatus()
    {
        // Given
        Neo4jError error1 = Neo4jError.from( Status.General.DatabaseUnavailable,  "foo" );
        Neo4jError error2 = Neo4jError.from( Status.Request.Invalid, "bar");
        Neo4jError error3 = Neo4jError.from( Status.Schema.ConstraintAlreadyExists, "baz");

        // When
        Neo4jError combine = Neo4jError.combine( asList( error1, error2, error3 ) );

        // Then
        assertThat( combine.status(), equalTo( Status.General.UnknownError) );
    }
}
