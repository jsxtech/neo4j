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
package org.neo4j.server.rest.dbms;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.neo4j.kernel.api.exceptions.InvalidArgumentsException;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.security.SecurityContext;
import org.neo4j.server.rest.repr.AuthorizationRepresentation;
import org.neo4j.server.rest.repr.BadInputException;
import org.neo4j.server.rest.repr.ExceptionRepresentation;
import org.neo4j.server.rest.repr.InputFormat;
import org.neo4j.server.rest.repr.OutputFormat;
import org.neo4j.server.rest.transactional.error.Neo4jError;
import org.neo4j.server.security.auth.User;
import org.neo4j.server.security.auth.UserManager;
import org.neo4j.server.security.auth.UserManagerSupplier;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.neo4j.server.rest.dbms.AuthorizedRequestWrapper.getSecurityContextFromUserPrincipal;
import static org.neo4j.server.rest.web.CustomStatusType.UNPROCESSABLE;

@Path("/user")
public class UserService
{
    public static final String PASSWORD = "password";

    private final UserManagerSupplier userManagerSupplier;
    private final InputFormat input;
    private final OutputFormat output;

    public UserService( @Context UserManagerSupplier userManagerSupplier, @Context InputFormat input, @Context OutputFormat
            output )
    {
        this.userManagerSupplier = userManagerSupplier;
        this.input = input;
        this.output = output;
    }

    @GET
    @Path("/{username}")
    public Response getUser( @PathParam("username") String username, @Context HttpServletRequest req )
    {
        Principal principal = req.getUserPrincipal();
        if ( principal == null || !principal.getName().equals( username ) )
        {
            return output.notFound();
        }

        SecurityContext securityContext = getSecurityContextFromUserPrincipal( principal );
        UserManager userManager = userManagerSupplier.getUserManager( securityContext );

        try
        {
            User user = userManager.getUser( username );
            return output.ok( new AuthorizationRepresentation( user ) );
        }
        catch ( InvalidArgumentsException e )
        {
            return output.notFound();
        }
    }

    @POST
    @Path("/{username}/password")
    public Response setPassword( @PathParam("username") String username, @Context HttpServletRequest req, String payload )
    {
        Principal principal = req.getUserPrincipal();
        if ( principal == null || !principal.getName().equals( username ) )
        {
            return output.notFound();
        }

        final Map<String, Object> deserialized;
        try
        {
            deserialized = input.readMap( payload );
        } catch ( BadInputException e )
        {
            return output.response( BAD_REQUEST, new ExceptionRepresentation(
                    new Neo4jError( Status.Request.InvalidFormat, e.getMessage() ) ) );
        }

        Object o = deserialized.get( PASSWORD );
        if ( o == null )
        {
            return output.response( UNPROCESSABLE, new ExceptionRepresentation(
                    new Neo4jError( Status.Request.InvalidFormat, String.format( "Required parameter '%s' is missing.", PASSWORD ) ) ) );
        }
        if ( !( o instanceof String ) )
        {
            return output.response( UNPROCESSABLE, new ExceptionRepresentation(
                    new Neo4jError( Status.Request.InvalidFormat, String.format( "Expected '%s' to be a string.", PASSWORD ) ) ) );
        }
        String newPassword = (String) o;

        try
        {
            SecurityContext securityContext = getSecurityContextFromUserPrincipal( principal );
            if ( securityContext == null )
            {
                return output.notFound();
            }
            else
            {
                UserManager userManager = userManagerSupplier.getUserManager( securityContext );
                userManager.setUserPassword( username, newPassword, false );
            }
        }
        catch ( IOException e )
        {
            return output.serverErrorWithoutLegacyStacktrace( e );
        }
        catch ( InvalidArgumentsException e )
        {
            return output.response( UNPROCESSABLE, new ExceptionRepresentation(
                    new Neo4jError( e.status(), e.getMessage() ) ) );
        }
        return output.ok();
    }
}
