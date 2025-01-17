/*
 * Copyright (c) 2002-2019 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.core.consensus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.neo4j.causalclustering.identity.ClusterId;
import org.neo4j.causalclustering.messaging.Message;
import org.neo4j.causalclustering.core.consensus.log.RaftLogEntry;
import org.neo4j.causalclustering.core.replication.ReplicatedContent;
import org.neo4j.causalclustering.identity.MemberId;

import static java.lang.String.format;
import static org.neo4j.causalclustering.core.consensus.RaftMessages.Type.HEARTBEAT_RESPONSE;
import static org.neo4j.causalclustering.core.consensus.RaftMessages.Type.PRUNE_REQUEST;

public interface RaftMessages
{
    enum Type
    {
        VOTE_REQUEST,
        VOTE_RESPONSE,

        APPEND_ENTRIES_REQUEST,
        APPEND_ENTRIES_RESPONSE,

        HEARTBEAT,
        HEARTBEAT_RESPONSE,
        LOG_COMPACTION_INFO,

        // Timeouts
        ELECTION_TIMEOUT,
        HEARTBEAT_TIMEOUT,

        // TODO: Refactor, these are client-facing messages / api. Perhaps not public and instantiated through an api
        // TODO: method instead?
        NEW_ENTRY_REQUEST,
        NEW_BATCH_REQUEST,

        PRUNE_REQUEST,
    }

    interface RaftMessage extends Message
    {
        MemberId from();
        Type type();
    }

    class Directed
    {
        MemberId to;
        RaftMessage message;

        public Directed( MemberId to, RaftMessage message )
        {
            this.to = to;
            this.message = message;
        }

        public MemberId to()
        {
            return to;
        }

        public RaftMessage message()
        {
            return message;
        }

        @Override
        public String toString()
        {
            return format( "Directed{to=%s, message=%s}", to, message );
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( o == null || getClass() != o.getClass() )
            {
                return false;
            }
            Directed directed = (Directed) o;
            return Objects.equals( to, directed.to ) && Objects.equals( message, directed.message );
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( to, message );
        }
    }

    interface Vote
    {
        class Request extends BaseRaftMessage
        {
            private long term;
            private MemberId candidate;
            private long lastLogIndex;
            private long lastLogTerm;

            public Request( MemberId from, long term, MemberId candidate, long lastLogIndex, long lastLogTerm )
            {
                super( from, Type.VOTE_REQUEST );
                this.term = term;
                this.candidate = candidate;
                this.lastLogIndex = lastLogIndex;
                this.lastLogTerm = lastLogTerm;
            }

            public long term()
            {
                return term;
            }

            @Override
            public boolean equals( Object o )
            {
                if ( this == o )
                {
                    return true;
                }
                if ( o == null || getClass() != o.getClass() )
                {
                    return false;
                }
                Request request = (Request) o;
                return lastLogIndex == request.lastLogIndex &&
                        lastLogTerm == request.lastLogTerm &&
                        term == request.term &&
                        candidate.equals( request.candidate );
            }

            @Override
            public int hashCode()
            {
                int result = (int) term;
                result = 31 * result + candidate.hashCode();
                result = 31 * result + (int) (lastLogIndex ^ (lastLogIndex >>> 32));
                result = 31 * result + (int) (lastLogTerm ^ (lastLogTerm >>> 32));
                return result;
            }

            @Override
            public String toString()
            {
                return format( "Vote.Request from %s {term=%d, candidate=%s, lastAppended=%d, lastLogTerm=%d}",
                        from, term, candidate, lastLogIndex, lastLogTerm );
            }

            public long lastLogTerm()
            {
                return lastLogTerm;
            }

            public long lastLogIndex()
            {
                return lastLogIndex;
            }

            public MemberId candidate()
            {
                return candidate;
            }
        }

        class Response extends BaseRaftMessage
        {
            private long term;
            private boolean voteGranted;

            public Response( MemberId from, long term, boolean voteGranted )
            {
                super( from, Type.VOTE_RESPONSE );
                this.term = term;
                this.voteGranted = voteGranted;
            }

            @Override
            public boolean equals( Object o )
            {
                if ( this == o )
                {
                    return true;
                }
                if ( o == null || getClass() != o.getClass() )
                {
                    return false;
                }

                Response response = (Response) o;

                return term == response.term && voteGranted == response.voteGranted;

            }

            @Override
            public int hashCode()
            {
                int result = (int) term;
                result = 31 * result + (voteGranted ? 1 : 0);
                return result;
            }

            @Override
            public String toString()
            {
                return format( "Vote.Response from %s {term=%d, voteGranted=%s}", from, term, voteGranted );
            }

            public long term()
            {
                return term;
            }

            public boolean voteGranted()
            {
                return voteGranted;
            }
        }
    }

    interface AppendEntries
    {
        class Request extends BaseRaftMessage
        {
            private long leaderTerm;
            private long prevLogIndex;
            private long prevLogTerm;
            private RaftLogEntry[] entries;
            private long leaderCommit;

            public Request( MemberId from, long leaderTerm, long prevLogIndex, long prevLogTerm,
                            RaftLogEntry[] entries, long leaderCommit )
            {
                super( from, Type.APPEND_ENTRIES_REQUEST );
                Objects.requireNonNull( entries );
                assert !((prevLogIndex == -1 && prevLogTerm != -1) || (prevLogTerm == -1 && prevLogIndex != -1)) :
                        format( "prevLogIndex was %d and prevLogTerm was %d", prevLogIndex, prevLogTerm );
                this.entries = entries;
                this.leaderTerm = leaderTerm;
                this.prevLogIndex = prevLogIndex;
                this.prevLogTerm = prevLogTerm;
                this.leaderCommit = leaderCommit;
            }

            public long leaderTerm()
            {
                return leaderTerm;
            }

            public long prevLogIndex()
            {
                return prevLogIndex;
            }

            public long prevLogTerm()
            {
                return prevLogTerm;
            }

            public RaftLogEntry[] entries()
            {
                return entries;
            }

            public long leaderCommit()
            {
                return leaderCommit;
            }

            @Override
            public boolean equals( Object o )
            {
                if ( this == o )
                {
                    return true;
                }
                if ( o == null || getClass() != o.getClass() )
                {
                    return false;
                }
                Request request = (Request) o;
                return Objects.equals( leaderTerm, request.leaderTerm ) &&
                        Objects.equals( prevLogIndex, request.prevLogIndex ) &&
                        Objects.equals( prevLogTerm, request.prevLogTerm ) &&
                        Objects.equals( leaderCommit, request.leaderCommit ) &&
                        Arrays.equals( entries, request.entries );
            }

            @Override
            public int hashCode()
            {
                return Objects.hash( leaderTerm, prevLogIndex, prevLogTerm, entries, leaderCommit );
            }

            @Override
            public String toString()
            {
                return format( "AppendEntries.Request from %s {leaderTerm=%d, prevLogIndex=%d, " +
                                "prevLogTerm=%d, entry=%s, leaderCommit=%d}",
                        from, leaderTerm, prevLogIndex, prevLogTerm, Arrays.toString( entries ), leaderCommit );
            }
        }

        class Response extends BaseRaftMessage
        {
            private long term;
            private boolean success;
            private long matchIndex;
            private long appendIndex;

            public Response( MemberId from, long term, boolean success, long matchIndex, long appendIndex )
            {
                super( from, Type.APPEND_ENTRIES_RESPONSE );
                this.term = term;
                this.success = success;
                this.matchIndex = matchIndex;
                this.appendIndex = appendIndex;
            }

            public long term()
            {
                return term;
            }

            public boolean success()
            {
                return success;
            }

            public long matchIndex()
            {
                return matchIndex;
            }

            public long appendIndex()
            {
                return appendIndex;
            }

            @Override
            public boolean equals( Object o )
            {
                if ( this == o )
                {
                    return true;
                }
                if ( o == null || getClass() != o.getClass() )
                {
                    return false;
                }
                if ( !super.equals( o ) )
                {
                    return false;
                }
                Response response = (Response) o;
                return term == response.term &&
                        success == response.success &&
                        matchIndex == response.matchIndex &&
                        appendIndex == response.appendIndex;
            }

            @Override
            public int hashCode()
            {
                return Objects.hash( super.hashCode(), term, success, matchIndex, appendIndex );
            }

            @Override
            public String toString()
            {
                return format( "AppendEntries.Response from %s {term=%d, success=%s, matchIndex=%d, appendIndex=%d}",
                        from, term, success, matchIndex, appendIndex );
            }
        }
    }

    class Heartbeat extends BaseRaftMessage
    {
        private long leaderTerm;
        private long commitIndex;
        private long commitIndexTerm;

        public Heartbeat( MemberId from, long leaderTerm, long commitIndex, long commitIndexTerm )
        {
            super( from, Type.HEARTBEAT );
            this.leaderTerm = leaderTerm;
            this.commitIndex = commitIndex;
            this.commitIndexTerm = commitIndexTerm;
        }

        public long leaderTerm()
        {
            return leaderTerm;
        }

        public long commitIndex()
        {
            return commitIndex;
        }

        public long commitIndexTerm()
        {
            return commitIndexTerm;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( o == null || getClass() != o.getClass() )
            {
                return false;
            }
            if ( !super.equals( o ) )
            {
                return false;
            }

            Heartbeat heartbeat = (Heartbeat) o;

            return leaderTerm == heartbeat.leaderTerm &&
                   commitIndex == heartbeat.commitIndex &&
                   commitIndexTerm == heartbeat.commitIndexTerm;
        }

        @Override
        public int hashCode()
        {
            int result = super.hashCode();
            result = 31 * result + (int) (leaderTerm ^ (leaderTerm >>> 32));
            result = 31 * result + (int) (commitIndex ^ (commitIndex >>> 32));
            result = 31 * result + (int) (commitIndexTerm ^ (commitIndexTerm >>> 32));
            return result;
        }

        @Override
        public String toString()
        {
            return format( "Heartbeat from %s {leaderTerm=%d, commitIndex=%d, commitIndexTerm=%d}", from, leaderTerm,
                    commitIndex, commitIndexTerm );
        }
    }

    class HeartbeatResponse extends BaseRaftMessage
    {

        public HeartbeatResponse( MemberId from )
        {
            super( from, HEARTBEAT_RESPONSE );
        }

        @Override
        public String toString()
        {
            return "HeartbeatResponse{from=" + from + "}";
        }
    }

    class LogCompactionInfo extends BaseRaftMessage
    {
        private long leaderTerm;
        private long prevIndex;

        public LogCompactionInfo( MemberId from, long leaderTerm, long prevIndex )
        {
            super( from, Type.LOG_COMPACTION_INFO );
            this.leaderTerm = leaderTerm;
            this.prevIndex = prevIndex;
        }

        public long leaderTerm()
        {
            return leaderTerm;
        }

        public long prevIndex()
        {
            return prevIndex;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( o == null || getClass() != o.getClass() )
            {
                return false;
            }
            if ( !super.equals( o ) )
            {
                return false;
            }

            LogCompactionInfo other = (LogCompactionInfo) o;

            return leaderTerm == other.leaderTerm &&
                   prevIndex == other.prevIndex;
        }

        @Override
        public int hashCode()
        {
            int result = super.hashCode();
            result = 31 * result + (int) (leaderTerm ^ (leaderTerm >>> 32));
            result = 31 * result + (int) (prevIndex ^ (prevIndex >>> 32));
            return result;
        }

        @Override
        public String toString()
        {
            return format( "Log compaction from %s {leaderTerm=%d, prevIndex=%d}", from, leaderTerm, prevIndex );
        }
    }

    interface Timeout
    {
        class Election extends BaseRaftMessage
        {
            public Election( MemberId from )
            {
                super( from, Type.ELECTION_TIMEOUT );
            }

            @Override
            public String toString()
            {
                return "Timeout.Election{}";
            }
        }

        class Heartbeat extends BaseRaftMessage
        {
            public Heartbeat( MemberId from )
            {
                super( from, Type.HEARTBEAT_TIMEOUT );
            }

            @Override
            public String toString()
            {
                return "Timeout.Heartbeat{}";
            }
        }
    }

    interface NewEntry
    {
        class Request extends BaseRaftMessage
        {
            private ReplicatedContent content;

            public Request( MemberId from, ReplicatedContent content )
            {
                super( from, Type.NEW_ENTRY_REQUEST );
                this.content = content;
            }

            @Override
            public String toString()
            {
                return format( "NewEntry.Request from %s {content=%s}", from, content );
            }

            @Override
            public boolean equals( Object o )
            {
                if ( this == o )
                {
                    return true;
                }
                if ( o == null || getClass() != o.getClass() )
                {
                    return false;
                }

                Request request = (Request) o;

                return !(content != null ? !content.equals( request.content ) : request.content != null);
            }

            @Override
            public int hashCode()
            {
                return content != null ? content.hashCode() : 0;
            }

            public ReplicatedContent content()
            {
                return content;
            }
        }

        class BatchRequest extends BaseRaftMessage
        {
            private List<ReplicatedContent> list;

            public BatchRequest( int batchSize )
            {
                super( null, Type.NEW_BATCH_REQUEST );
                list = new ArrayList<>( batchSize );
            }

            public void add( ReplicatedContent content )
            {
                list.add( content );
            }

            @Override
            public boolean equals( Object o )
            {
                if ( this == o )
                { return true; }
                if ( o == null || getClass() != o.getClass() )
                { return false; }
                if ( !super.equals( o ) )
                { return false; }
                BatchRequest batchRequest = (BatchRequest) o;
                return Objects.equals( list, batchRequest.list );
            }

            @Override
            public int hashCode()
            {
                return Objects.hash( super.hashCode(), list );
            }

            @Override
            public String toString()
            {
                return "BatchRequest{" +
                       "list=" + list +
                       '}';
            }

            public List<ReplicatedContent> contents()
            {
                return Collections.unmodifiableList( list );
            }
        }
    }

    class ClusterIdAwareMessage implements RaftMessage
    {
        private final ClusterId clusterId;
        private final RaftMessage message;

        public ClusterIdAwareMessage( ClusterId clusterId, RaftMessage message )
        {
            Objects.requireNonNull( message );
            this.clusterId = clusterId;
            this.message = message;
        }

        public ClusterId clusterId()
        {
            return clusterId;
        }

        public RaftMessage message()
        {
            return message;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( o == null || getClass() != o.getClass() )
            {
                return false;
            }
            ClusterIdAwareMessage that = (ClusterIdAwareMessage) o;
            return Objects.equals( clusterId, that.clusterId ) && Objects.equals( message, that.message );
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( clusterId, message );
        }

        @Override
        public String toString()
        {
            return format( "{clusterId: %s, message: %s}", clusterId, message );
        }

        @Override
        public MemberId from()
        {
            return message.from();
        }

        @Override
        public Type type()
        {
            return message.type();
        }
    }

    class PruneRequest extends BaseRaftMessage
    {
        private final long pruneIndex;

        public PruneRequest( long pruneIndex )
        {
            super( null, PRUNE_REQUEST );
            this.pruneIndex = pruneIndex;
        }

        public long pruneIndex()
        {
            return pruneIndex;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            { return true; }
            if ( o == null || getClass() != o.getClass() )
            { return false; }
            if ( !super.equals( o ) )
            { return false; }
            PruneRequest that = (PruneRequest) o;
            return pruneIndex == that.pruneIndex;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( super.hashCode(), pruneIndex );
        }
    }

    abstract class BaseRaftMessage implements RaftMessage
    {
        protected MemberId from;
        private Type type;

        BaseRaftMessage( MemberId from, Type type )
        {
            this.from = from;
            this.type = type;
        }

        @Override
        public MemberId from()
        {
            return from;
        }

        @Override
        public Type type()
        {
            return type;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
            {
                return true;
            }
            if ( o == null || getClass() != o.getClass() )
            {
                return false;
            }
            BaseRaftMessage that = (BaseRaftMessage) o;
            return Objects.equals( from, that.from ) && type == that.type;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( from, type );
        }
    }
}
