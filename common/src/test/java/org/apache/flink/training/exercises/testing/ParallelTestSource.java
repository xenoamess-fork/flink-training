package org.apache.flink.training.exercises.testing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.*;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.core.io.SimpleVersionedSerializer;

import javax.annotation.Nullable;

import java.io.*;
import java.util.*;


public class ParallelTestSource<T> implements Source<T, ParallelTestSource.InMemorySplit<T>, List<T>>, ResultTypeQueryable<T> {

    private final List<T> elements;

    public ParallelTestSource(T... elements)  {
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
            SplitEnumeratorContext<InMemorySplit<T>> enumContext,
            List<T> checkpoint) {
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
     * Split 定义：每个分片只包含一段数据
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
     * SplitEnumerator：把数据平均切成 N 份，N = 并行度
     */
    public static class InMemoryEnumerator<T> implements SplitEnumerator<InMemorySplit<T>, List<T>> {

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
            // 当所有 reader 都注册后一次性分配
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
                if (from >= to) break;
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
     * SourceReader：真正读取数据
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
            if (! initialized.get()){
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
                initialized.set(true);
            }
        }

        @Override
        public void notifyNoMoreSplits() {
        }

        @Override
        public void close() {
        }
    }

    /* -------------------------------------------------------------
       序列化器（简单实现，实际生产可优化）
       ------------------------------------------------------------- */

    public static class InMemorySplitSerializer<T> implements SimpleVersionedSerializer<InMemorySplit<T>> {
        // 这里简化：仅支持 String，生产环境请用 Kryo/Avro/Protobuf
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
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
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