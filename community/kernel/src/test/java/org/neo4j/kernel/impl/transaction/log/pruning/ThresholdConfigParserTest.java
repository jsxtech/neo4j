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
package org.neo4j.kernel.impl.transaction.log.pruning;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.neo4j.kernel.impl.transaction.log.pruning.ThresholdConfigParser.ThresholdConfigValue;
import static org.neo4j.kernel.impl.transaction.log.pruning.ThresholdConfigParser.ThresholdConfigValue.KEEP_LAST_FILE;
import static org.neo4j.kernel.impl.transaction.log.pruning.ThresholdConfigParser.ThresholdConfigValue.NO_PRUNING;
import static org.neo4j.kernel.impl.transaction.log.pruning.ThresholdConfigParser.parse;

public class ThresholdConfigParserTest
{
    @Test
    public void parseTrue() throws Exception
    {
        ThresholdConfigValue configValue = parse( "true" );
        assertEquals( NO_PRUNING, configValue );
    }

    @Test
    public void parseFalse() throws Exception
    {
        ThresholdConfigValue configValue = parse( "false" );
        assertEquals( KEEP_LAST_FILE, configValue );
    }

    @Test(expected=IllegalArgumentException.class)
    public void parseGarbage() throws Exception
    {
        parse( "davide" );
        fail("Expected IllegalArgumentException");
    }
}
