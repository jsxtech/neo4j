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
package org.neo4j.kernel.impl.api.operations;

import java.util.Map;

import org.neo4j.kernel.api.LegacyIndexHits;
import org.neo4j.kernel.api.exceptions.legacyindex.LegacyIndexNotFoundKernelException;
import org.neo4j.kernel.impl.api.KernelStatement;

public interface LegacyIndexReadOperations
{
    /**
     * @param statement {@link KernelStatement} to use for state.
     * @param indexName name of node index to check for existence.
     * @param customConfiguration if {@code null} the configuration of existing won't be matched, otherwise it will
     * be matched and a mismatch will throw {@link IllegalArgumentException}.
     * @return whether or not node legacy index with name {@code indexName} exists.
     * @throws IllegalArgumentException on index existence with provided mismatching {@code customConfiguration}.
     */
    boolean nodeLegacyIndexExists( KernelStatement statement, String indexName, Map<String,String> customConfiguration );

    /**
     * @param statement {@link KernelStatement} to use for state.
     * @param indexName name of relationship index to check for existence.
     * @param customConfiguration if {@code null} the configuration of existing won't be matched, otherwise it will
     * be matched and a mismatch will throw {@link IllegalArgumentException}.
     * @return whether or not relationship legacy index with name {@code indexName} exists.
     * @throws IllegalArgumentException on index existence with provided mismatching {@code customConfiguration}.
     */
    boolean relationshipLegacyIndexExists( KernelStatement statement, String indexName, Map<String,String> customConfiguration );

    Map<String, String> nodeLegacyIndexGetConfiguration( KernelStatement statement, String indexName )
            throws LegacyIndexNotFoundKernelException;

    Map<String, String> relationshipLegacyIndexGetConfiguration( KernelStatement statement, String indexName )
            throws LegacyIndexNotFoundKernelException;

    LegacyIndexHits nodeLegacyIndexGet( KernelStatement statement, String indexName, String key, Object value )
            throws LegacyIndexNotFoundKernelException;

    LegacyIndexHits nodeLegacyIndexQuery( KernelStatement statement, String indexName, String key,
            Object queryOrQueryObject ) throws LegacyIndexNotFoundKernelException;

    LegacyIndexHits nodeLegacyIndexQuery( KernelStatement statement, String indexName, Object queryOrQueryObject )
            throws LegacyIndexNotFoundKernelException;

    LegacyIndexHits relationshipLegacyIndexGet( KernelStatement statement, String indexName, String key, Object value,
            long startNode, long endNode ) throws LegacyIndexNotFoundKernelException;

    LegacyIndexHits relationshipLegacyIndexQuery( KernelStatement statement, String indexName, String key,
            Object queryOrQueryObject, long startNode, long endNode ) throws LegacyIndexNotFoundKernelException;

    LegacyIndexHits relationshipLegacyIndexQuery( KernelStatement statement, String indexName,
            Object queryOrQueryObject, long startNode, long endNode )
            throws LegacyIndexNotFoundKernelException;

    String[] nodeLegacyIndexesGetAll( KernelStatement statement );

    String[] relationshipLegacyIndexesGetAll( KernelStatement statement );
}
