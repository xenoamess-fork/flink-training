/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.training.exercises.testing;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SourceSplit;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.core.io.SimpleVersionedSerializer;

import javax.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ParallelTestSource<T>
        implements Source<T, ParallelTestSource.InMemorySplit<T>, List<T>>, ResultTypeQueryable<T> {

    private final List<T> elements;

    public ParallelTestSource(T... elements) {
        this(new ArrayList<>(List.of(elements)));
    }

    public ParallelTestSource(List<T> elements) {
        this.elements = elements;
    }

    @Override
    public Boundedness getBoundedness() {
        // 这批数据是有限的
        return Boundedness.BOUNDED;
    }

    @Override
    public SourceReader<T, InMemorySplit<T>> createReader(SourceReaderContext ctx) {
        return new InMemoryReader(elements);
    }

    @Override
    public SplitEnumerator<InMemorySplit<T>, List<T>> createEnumerator(
            SplitEnumeratorContext<InMemorySplit<T>> enumContext) {
        return new InMemoryEnumerator<>(enumContext, elements);
    }

    @Override
    public SplitEnumerator<InMemorySplit<T>, List<T>> restoreEnumerator(
            SplitEnumeratorContext<InMemorySplit<T>> enumContext, List<T> checkpoint) {
        return new InMemoryEnumerator<>(enumContext, checkpoint);
    }

    @Override
    public SimpleVersionedSerializer<InMemorySplit<T>> getSplitSerializer() {
        return new InMemorySplitSerializer();
    }

    @Override
    public SimpleVersionedSerializer<List<T>> getEnumeratorCheckpointSerializer() {
        return new CheckpointSerializer<>();
    }

    @Override
    public TypeInformation<T> getProducedType() {
        //noinspection unchecked
        return (TypeInformation<T>) TypeInformation.of(elements.get(0).getClass());
    }

    /**
     * Split definition for in-memory data.
     */
    public static class InMemorySplit<T> implements SourceSplit, Serializable {
        private final int splitId;
        private final List<T> slice;

        InMemorySplit(int splitId, List<T> slice) {
            this.splitId = splitId;
            this.slice = new ArrayList<>(slice);
        }

        @Override
        public String splitId() {
            return "split-" + splitId;
        }

        public List<T> getSlice() {
            return slice;
        }
    }

    /**
     * SplitEnumerator：split data.
     */
    public static class InMemoryEnumerator<T>
            implements SplitEnumerator<InMemorySplit<T>, List<T>> {

        private final SplitEnumeratorContext<InMemorySplit<T>> context;
        private final List<T> elements;
        private boolean assigned = false;

        InMemoryEnumerator(SplitEnumeratorContext<InMemorySplit<T>> context, List<T> elements) {
            this.context = context;
            this.elements = elements;
        }

        @Override
        public void start() {
        }

        @Override
        public void handleSplitRequest(int subtaskId, @Nullable String requesterHostname) {
        }

        @Override
        public void addSplitsBack(List<InMemorySplit<T>> splits, int subtaskId) {
        }

        @Override
        public void addReader(int subtaskId) {
            if (!assigned && context.registeredReaders().size() == context.currentParallelism()) {
                assignSplits();
                assigned = true;
            }
        }

        private void assignSplits() {
            int parallelism = context.currentParallelism();
            int step = Math.max(1, (elements.size() + parallelism - 1) / parallelism);
            for (int i = 0; i < parallelism; i++) {
                int from = i * step;
                int to = Math.min(from + step, elements.size());
                if (from >= to) {
                    while (i < parallelism) {
                        InMemorySplit<T> split = new InMemorySplit<>(i, List.of());
                        context.assignSplit(split, i);
                        i++;
                    }
                    break;
                }
                InMemorySplit<T> split = new InMemorySplit<>(i, elements.subList(from, to));
                context.assignSplit(split, i);
            }
        }

        @Override
        public List<T> snapshotState(long checkpointId) {
            return elements;
        }

        @Override
        public void close() {
        }
    }

    /**
     * SourceReader: read data.
     */
    public static class InMemoryReader<T> implements SourceReader<T, InMemorySplit<T>> {

        private final List<T> allElements;
        private final Queue<T> remaining = new ArrayDeque<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        public InMemoryReader(List<T> allElements) {
            this.allElements = allElements;
        }

        @Override
        public void start() {
        }

        @Override
        public InputStatus pollNext(ReaderOutput<T> output) {
            if (!initialized.get()) {
                return InputStatus.MORE_AVAILABLE;
            }
            T next = remaining.poll();
            if (next != null) {
                output.collect(next);
                return InputStatus.MORE_AVAILABLE;
            } else {
                return InputStatus.END_OF_INPUT;
            }
        }

        @Override
        public List<InMemorySplit<T>> snapshotState(long checkpointId) {
            return Collections.emptyList();
        }

        @Override
        public CompletableFuture<Void> isAvailable() {
            return CompletableFuture.completedFuture((Void) null);
        }

        @Override
        public void addSplits(List<InMemorySplit<T>> splits) {
            for (InMemorySplit<T> split : splits) {
                remaining.addAll(split.getSlice());
            }
            initialized.set(true);
        }

        @Override
        public void notifyNoMoreSplits() {
        }

        @Override
        public void close() {
        }
    }

    public static class InMemorySplitSerializer<T>
            implements SimpleVersionedSerializer<InMemorySplit<T>> {
        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public byte[] serialize(InMemorySplit split) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(split);
            return bos.toByteArray();
        }

        @Override
        public InMemorySplit<T> deserialize(int version, byte[] serialized) throws IOException {
            try (ObjectInputStream ois =
                         new ObjectInputStream(new ByteArrayInputStream(serialized))) {
                return (InMemorySplit<T>) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }
    }

    public static class CheckpointSerializer<T> implements SimpleVersionedSerializer<List<T>> {
        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public byte[] serialize(List<T> obj) throws IOException {
            return new byte[0];
        }

        @Override
        public List<T> deserialize(int version, byte[] serialized) {
            return Collections.emptyList();
        }
    }
}
