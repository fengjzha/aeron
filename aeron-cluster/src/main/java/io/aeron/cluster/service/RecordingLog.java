/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster.service;

import io.aeron.archive.client.AeronArchive;
import org.agrona.BitUtil;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static java.nio.file.StandardOpenOption.*;
import static org.agrona.BitUtil.CACHE_LINE_LENGTH;
import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * An log of recordings that make up the history of a Raft log. Entries are in chronological order.
 * <p>
 * The log is made up of entries of log terms or snapshots to roll up state as of a log position and leadership term.
 * <p>
 * The latest state is made up of a the latest snapshot followed by any term logs which follow. It is possible that
 * the a snapshot is taken mid term and therefore the latest state is the snapshot plus the log of messages which
 * begin before the snapshot but continues after it.
 * <p>
 * Record layout as follows:
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                        Recording ID                           |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |         Log Position at beginning of term or snapshot         |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                     Leadership Term ID                        |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |   Timestamp at beginning of term or when snapshot was taken   |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 *  |                  Entry Type (Log or Snapshot)                 |
 *  +---------------------------------------------------------------+
 *  |                                                               |
 *  |                                                              ...
 * ...                 Repeats to the end of the log                |
 *  |                                                               |
 *  +---------------------------------------------------------------+
 * </pre>
 */
public class RecordingLog
{
    /**
     * A copy of the entry in the log.
     */
    public static final class Entry
    {
        public final long recordingId;
        public final long logPosition;
        public final long leadershipTermId;
        public final long timestamp;
        public final int type;

        public Entry(
            final long recordingId,
            final long logPosition,
            final long leadershipTermId,
            final long timestamp,
            final int type)
        {
            this.recordingId = recordingId;
            this.logPosition = logPosition;
            this.leadershipTermId = leadershipTermId;
            this.timestamp = timestamp;
            this.type = type;
        }

        public String toString()
        {
            return "Entry{" +
                "recordingId=" + recordingId +
                ", logPosition=" + logPosition +
                ", leadershipTermId=" + leadershipTermId +
                ", timestamp=" + timestamp +
                ", type=" + type +
                '}';
        }

        public void confirmMatch(final long logPosition, final long leadershipTermId, final long timestamp)
        {
            if (logPosition != this.logPosition)
            {
                throw new IllegalStateException(
                    "Log position does not match: this=" + this.logPosition + " that=" + logPosition);
            }

            if (leadershipTermId != this.leadershipTermId)
            {
                throw new IllegalStateException(
                    "Leadership term id does not match: this=" + this.leadershipTermId + " that=" + leadershipTermId);
            }

            if (timestamp != this.timestamp)
            {
                throw new IllegalStateException(
                    "Timestamp does not match: this=" + this.timestamp + " that=" + timestamp);
            }
        }
    }

    /**
     * Steps in a recovery plan.
     */
    public static class ReplayStep
    {
        public final long recordingStartPosition;
        public final long recordingStopPosition;
        public final Entry entry;

        public ReplayStep(final long recordingStartPosition, final long recordingStopPosition, final Entry entry)
        {
            this.recordingStartPosition = recordingStartPosition;
            this.recordingStopPosition = recordingStopPosition;
            this.entry = entry;
        }

        public String toString()
        {
            return "ReplayStep{" +
                ", recordingStartPosition=" + recordingStartPosition +
                ", recordingStopPosition=" + recordingStopPosition +
                ", entry=" + entry +
                '}';
        }
    }

    /**
     * The snapshot and steps to recover the state of a cluster.
     */
    public static class RecoveryPlan
    {
        public final ReplayStep snapshotStep;
        public final ArrayList<ReplayStep> termSteps;

        public RecoveryPlan(final ReplayStep snapshotStep, final ArrayList<ReplayStep> termSteps)
        {
            this.snapshotStep = snapshotStep;
            this.termSteps = termSteps;
        }
    }

    /**
     * Filename for the recording index for the history of log terms and snapshots.
     */
    public static final String RECORDING_INDEX_FILE_NAME = "recording-index.log";

    /**
     * The index entry is for a recording of messages within a term to the consensus log.
     */
    public static final int ENTRY_TYPE_TERM = 0;

    /**
     * The index entry is for a recording of a snapshot of state taken as of a position in the log.
     */
    public static final int ENTRY_TYPE_SNAPSHOT = 1;

    /**
     * The offset at which the recording id for the entry is stored.
     */
    public static final int RECORDING_ID_OFFSET = 0;

    /**
     * The offset at which the absolute log position for the entry is stored.
     */
    public static final int LOG_POSITION_OFFSET = RECORDING_ID_OFFSET + SIZE_OF_LONG;

    /**
     * The offset at which the leadership term id for the entry is stored.
     */
    public static final int LEADERSHIP_TERM_ID_OFFSET = LOG_POSITION_OFFSET + SIZE_OF_LONG;

    /**
     * The offset at which the timestamp for the entry is stored.
     */
    public static final int TIMESTAMP_OFFSET = LEADERSHIP_TERM_ID_OFFSET + SIZE_OF_LONG;

    /**
     * The offset at which the type of the entry is stored.
     */
    public static final int ENTRY_TYPE_OFFSET = TIMESTAMP_OFFSET + SIZE_OF_LONG;

    /**
     * The length of each entry.
     */
    private static final int ENTRY_LENGTH = BitUtil.align(ENTRY_TYPE_OFFSET + SIZE_OF_LONG, CACHE_LINE_LENGTH);

    private final File parentDir;
    private final File indexFile;
    private final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4096);
    private final UnsafeBuffer buffer = new UnsafeBuffer(byteBuffer);
    private final ArrayList<Entry> entries = new ArrayList<>();

    /**
     * Create an index that appends to an existing index or creates a new one.
     *
     * @param parentDir in which the index will be created.
     */
    public RecordingLog(final File parentDir)
    {
        this.parentDir = parentDir;
        this.indexFile = new File(parentDir, RECORDING_INDEX_FILE_NAME);

        reload();
    }

    /**
     * List of currently loaded entries.
     *
     * @return the list of currently loaded entries.
     */
    public List<Entry> entries()
    {
        return entries;
    }

    /**
     * Reload the index from disk.
     */
    public void reload()
    {
        entries.clear();

        FileChannel fileChannel = null;
        try
        {
            final boolean newFile = !indexFile.exists();
            fileChannel = FileChannel.open(indexFile.toPath(), CREATE, READ, WRITE, SYNC);

            if (newFile)
            {
                syncDirectory(parentDir);
                return;
            }

            byteBuffer.clear();
            while (true)
            {
                final int bytes = fileChannel.read(byteBuffer);
                if (byteBuffer.remaining() == 0)
                {
                    byteBuffer.flip();
                    captureEntriesFromBuffer(byteBuffer, buffer, entries);
                    byteBuffer.clear();
                }

                if (-1 == bytes)
                {
                    if (byteBuffer.position() > 0)
                    {
                        byteBuffer.flip();
                        captureEntriesFromBuffer(byteBuffer, buffer, entries);
                        byteBuffer.clear();
                    }

                    break;
                }
            }

        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
        finally
        {
            CloseHelper.close(fileChannel);
        }
    }

    /**
     * Get the latest snapshot {@link Entry} in the index.
     *
     * @return the latest snapshot {@link Entry} in the index or null if no snapshot exists.
     */
    public Entry getLatestSnapshot()
    {
        for (int i = entries.size() - 1; i >= 0; i--)
        {
            final Entry entry = entries.get(i);
            if (ENTRY_TYPE_SNAPSHOT == entry.type)
            {
                return entry;
            }
        }

        return null;
    }

    /**
     * Create a recovery plan for the cluster that when the steps are replayed will bring the cluster back to the
     * latest stable state.
     *
     * @param archive to lookup recording descriptors.
     * @return a new {@link RecoveryPlan} for the cluster.
     */
    public RecoveryPlan createRecoveryPlan(final AeronArchive archive)
    {
        final ArrayList<ReplayStep> steps = new ArrayList<>();

        return new RecoveryPlan(planRecovery(steps, entries, archive), steps);
    }

    static ReplayStep planRecovery(
        final ArrayList<ReplayStep> steps, final ArrayList<Entry> entries, final AeronArchive archive)
    {
        if (entries.isEmpty())
        {
            return null;
        }

        int snapshotIndex = -1;
        for (int i = entries.size() - 1; i >= 0; i--)
        {
            final Entry entry = entries.get(i);
            if (ENTRY_TYPE_SNAPSHOT == entry.type)
            {
                snapshotIndex = i;
            }
        }

        final ReplayStep snapshotStep;
        final RecordingExtent recordingExtent = new RecordingExtent();

        if (-1 != snapshotIndex)
        {
            final Entry snapshot = entries.get(snapshotIndex);
            getRecordingExtent(archive, recordingExtent, snapshot);

            snapshotStep = new ReplayStep(recordingExtent.startPosition, recordingExtent.stopPosition, snapshot);

            if (snapshotIndex - 1 >= 0)
            {
                for (int i = snapshotIndex - 1; i >= 0; i--)
                {
                    final Entry entry = entries.get(i);
                    if (ENTRY_TYPE_TERM == entry.type)
                    {
                        getRecordingExtent(archive, recordingExtent, snapshot);

                        if (recordingExtent.stopPosition == NULL_POSITION ||
                            (entry.logPosition + recordingExtent.stopPosition) > snapshot.logPosition)
                        {
                            final long replayRecordingFromPosition = snapshot.logPosition - entry.logPosition;
                            steps.add(new ReplayStep(replayRecordingFromPosition, recordingExtent.stopPosition, entry));
                        }
                        break;
                    }
                }
            }
        }
        else
        {
            snapshotStep = null;
        }

        for (int i = snapshotIndex + 1, length = entries.size(); i < length; i++)
        {
            final Entry entry = entries.get(i);
            getRecordingExtent(archive, recordingExtent, entry);

            steps.add(new ReplayStep(recordingExtent.startPosition, recordingExtent.stopPosition, entry));
        }

        return snapshotStep;
    }

    /**
     * Get the latest snapshot for a given position.
     *
     * @param position to match the snapshot.
     * @return the latest snapshot for a given position or null if no match found.
     */
    public Entry getSnapshotByPosition(final long position)
    {
        for (int i = entries.size() - 1; i >= 0; i--)
        {
            final Entry entry = entries.get(i);
            if (position == entry.logPosition)
            {
                return entry;
            }
        }

        return null;
    }

    /**
     * Append an index entry for a Raft term.
     *
     * @param recordingId      in the archive for the term.
     * @param logPosition      reached at the beginning of the term.
     * @param leadershipTermId for the current term.
     * @param timestamp        at the beginning of the term.
     */
    public void appendTerm(
        final long recordingId, final long logPosition, final long leadershipTermId, final long timestamp)
    {
        append(ENTRY_TYPE_TERM, recordingId, logPosition, leadershipTermId, timestamp);
    }

    /**
     * Append an index entry for a snapshot.
     *
     * @param recordingId      in the archive for the snapshot.
     * @param logPosition      reached for the snapshot.
     * @param leadershipTermId for the current term
     * @param timestamp        at which the snapshot was taken.
     */
    public void appendSnapshot(
        final long recordingId, final long logPosition, final long leadershipTermId, final long timestamp)
    {
        append(ENTRY_TYPE_SNAPSHOT, recordingId, logPosition, leadershipTermId, timestamp);
    }

    private void append(
        final int entryType,
        final long recordingId,
        final long logPosition,
        final long leadershipTermId,
        final long timestamp)
    {
        buffer.putLong(RECORDING_ID_OFFSET, recordingId);
        buffer.putLong(LOG_POSITION_OFFSET, logPosition);
        buffer.putLong(LEADERSHIP_TERM_ID_OFFSET, leadershipTermId);
        buffer.putLong(TIMESTAMP_OFFSET, timestamp);
        buffer.putInt(ENTRY_TYPE_OFFSET, entryType);

        byteBuffer.limit(ENTRY_LENGTH).position(0);

        try (FileChannel fileChannel = FileChannel.open(indexFile.toPath(), WRITE, APPEND, SYNC))
        {
            fileChannel.write(byteBuffer);
        }
        catch (final Exception ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        entries.add(new Entry(recordingId, logPosition, leadershipTermId, timestamp, entryType));
    }

    private static void captureEntriesFromBuffer(
        final ByteBuffer byteBuffer, final UnsafeBuffer buffer, final ArrayList<Entry> entries)
    {
        for (int i = 0, length = byteBuffer.limit(); i < length; i += ENTRY_LENGTH)
        {
            entries.add(new Entry(
                buffer.getLong(i + RECORDING_ID_OFFSET),
                buffer.getLong(i + LOG_POSITION_OFFSET),
                buffer.getLong(i + LEADERSHIP_TERM_ID_OFFSET),
                buffer.getLong(i + TIMESTAMP_OFFSET),
                buffer.getInt(i + ENTRY_TYPE_OFFSET)));
        }
    }

    private static void syncDirectory(final File dir)
    {
        try (FileChannel fileChannel = FileChannel.open(dir.toPath()))
        {
            fileChannel.force(true);
        }
        catch (final IOException ignore)
        {
        }
    }

    private static void getRecordingExtent(
        final AeronArchive archive, final RecordingExtent recordingExtent, final Entry entry)
    {
        if (archive.listRecording(entry.recordingId, recordingExtent) == 0)
        {
            throw new IllegalStateException("Unknown recording id: " + entry.recordingId);
        }
    }
}
