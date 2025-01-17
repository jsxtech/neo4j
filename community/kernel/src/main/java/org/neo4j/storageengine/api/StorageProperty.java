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
package org.neo4j.storageengine.api;

import org.neo4j.kernel.api.exceptions.PropertyNotFoundException;

/**
 * Abstraction pairing property key token id and property value. Can represent both defined and undefined
 * property values, distinguished by {@link #isDefined()}. Undefined property instances can be used for
 * passing property key id and signaling that it doesn't exist.
 */
public interface StorageProperty
{
    /**
     * @return property key token id for this property.
     */
    int propertyKeyId();

    /**
     * @param other value to compare with for equality.
     * @return whether or not the property value is equal to the given {@code value}.
     */
    boolean valueEquals( Object other );

    /**
     * @return the property value.
     * @throws PropertyNotFoundException if this property instance represented a non-existent property.
     */
    Object value() throws PropertyNotFoundException;

    /**
     * @param defaultValue value to return if this property has no value associated with it, instead
     * of throwing exception.
     * @return the property value.
     */
    Object value( Object defaultValue );

    /**
     * @return the property value as, a {@link String} representation of it.
     * @throws PropertyNotFoundException if this property instance represented a non-existent property.
     */
    String valueAsString() throws PropertyNotFoundException;

    /**
     * @return whether or not the property is defined, e.g. if it exists (has a value) or not.
     */
    boolean isDefined();
}
