package org.apache.flink.training.exercises.testing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.connector.source.*;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;

import javax.annotation.Nullable;

import java.io.*;
import java.util.*;
import org.apache.flink.training.exercises.common.datatypes.TaxiRide;


public class ParallelTestSource implements Source<TaxiRide, ParallelTestSource.InMemorySplit<TaxiRide>, List<TaxiRide>> {

    private final List<TaxiRide> elements;

    public ParallelTestSource(TaxiRide... elements)  {
        this(new ArrayList<>(List.of(elements)));
    }

    public ParallelTestSource(List<TaxiRide> elements) {
        this.elements = elements;
    }

    @Override
    public Boundedness getBoundedness() {
        // 这批数据是有限的
        return Boundedness.BOUNDED;
    }

    @Override
    public SourceReader<TaxiRide, InMemorySplit<TaxiRide>> createReader(SourceReaderContext ctx) {
        return new InMemoryReader(elements);
    }

    @Override
    public SplitEnumerator<InMemorySplit<TaxiRide>, List<TaxiRide>> createEnumerator(
            SplitEnumeratorContext<InMemorySplit<TaxiRide>> enumContext) {
        return new InMemoryEnumerator<>(enumContext, elements);
    }

    @Override
    public SplitEnumerator<InMemorySplit<TaxiRide>, List<TaxiRide>> restoreEnumerator(
            SplitEnumeratorContext<InMemorySplit<TaxiRide>> enumContext,
            List<TaxiRide> checkpoint) {
        return new InMemoryEnumerator<>(enumContext, checkpoint);
    }

    @Override
    public SimpleVersionedSerializer<InMemorySplit<TaxiRide>> getSplitSerializer() {
        return new InMemorySplitSerializer();
    }

    @Override
    public SimpleVersionedSerializer<List<TaxiRide>> getEnumeratorCheckpointSerializer() {
        return new CheckpointSerializer<>();
    }


    /**
     * Split 定义：每个分片只包含一段数据
     */
    public static class InMemorySplit<TaxiRide> implements SourceSplit, Serializable {
        private final int splitId;
        private final List<TaxiRide> slice;

        InMemorySplit(int splitId, List<TaxiRide> slice) {
            this.splitId = splitId;
            this.slice = new ArrayList<>(slice);
        }

        @Override
        public String splitId() {
            return "split-" + splitId;
        }

        public List<TaxiRide> getSlice() {
            return slice;
        }
    }

    /**
     * SplitEnumerator：把数据平均切成 N 份，N = 并行度
     */
    public static class InMemoryEnumerator<TaxiRide> implements SplitEnumerator<InMemorySplit<TaxiRide>, List<TaxiRide>> {

        private final SplitEnumeratorContext<InMemorySplit<TaxiRide>> context;
        private final List<TaxiRide> elements;
        private boolean assigned = false;

        InMemoryEnumerator(SplitEnumeratorContext<InMemorySplit<TaxiRide>> context, List<TaxiRide> elements) {
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
        public void addSplitsBack(List<InMemorySplit<TaxiRide>> splits, int subtaskId) {
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
                InMemorySplit<TaxiRide> split = new InMemorySplit<>(i, elements.subList(from, to));
                context.assignSplit(split, i);
            }
        }

        @Override
        public List<TaxiRide> snapshotState(long checkpointId) {
            return elements;
        }

        @Override
        public void close() {
        }
    }

    /**
     * SourceReader：真正读取数据
     */
    public static class InMemoryReader implements SourceReader<TaxiRide, InMemorySplit<TaxiRide>> {

        private final List<TaxiRide> allElements;
        private final Queue<TaxiRide> remaining = new ArrayDeque<>();
        private final AtomicBoolean initialized = new AtomicBoolean(false);

        public InMemoryReader(List<TaxiRide> allElements) {
            this.allElements = allElements;
        }

        @Override
        public void start() {
        }

        @Override
        public InputStatus pollNext(ReaderOutput<TaxiRide> output) {
            if (! initialized.get()){
                         return InputStatus.MORE_AVAILABLE;
            }
            TaxiRide next = remaining.poll();
            if (next != null) {
                output.collect(next);
                return InputStatus.MORE_AVAILABLE;
            } else {
                return InputStatus.END_OF_INPUT;
            }
        }

        @Override
        public List<InMemorySplit<TaxiRide>> snapshotState(long checkpointId) {
            return Collections.emptyList();
        }

        @Override
        public CompletableFuture<Void> isAvailable() {
            return CompletableFuture.completedFuture((Void) null);
        }

        @Override
        public void addSplits(List<InMemorySplit<TaxiRide>> splits) {
            for (InMemorySplit<TaxiRide> split : splits) {
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

    public static class InMemorySplitSerializer implements SimpleVersionedSerializer<InMemorySplit<TaxiRide>> {
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
        public InMemorySplit<TaxiRide> deserialize(int version, byte[] serialized) throws IOException {
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
                return (InMemorySplit<TaxiRide>) ois.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException(e);
            }
        }
    }

    public static class CheckpointSerializer<TaxiRide> implements SimpleVersionedSerializer<List<TaxiRide>> {
        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public byte[] serialize(List<TaxiRide> obj) throws IOException {
            return new byte[0];
        }

        @Override
        public List<TaxiRide> deserialize(int version, byte[] serialized) {
            return Collections.emptyList();
        }
    }
}