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

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.accumulators.ListAccumulator;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;

import java.util.List;

public class TestSink<OUT> extends RichSinkFunction<OUT> {

    private final String name;

    public TestSink(String name) {
        this.name = name;
    }

    public TestSink() {
        this("results");
    }

    @Override
    public void open(OpenContext parameters) {
        getRuntimeContext().addAccumulator(name, new ListAccumulator<OUT>());
    }

    @Override
    public void invoke(OUT value, Context context) {
        getRuntimeContext().getAccumulator(name).add(value);
    }

    public List<OUT> getResults(JobExecutionResult jobResult) {
        return jobResult.getAccumulatorResult(name);
    }
}
