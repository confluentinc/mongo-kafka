/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.kafka.connect.util.jmx.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.mongodb.kafka.connect.util.VisibleForTesting;

/**
 * How long, in milliseconds, the oldest command that has started but not yet finished has been
 * running, or 0 when none is running. Unlike a metric sampled when a command finishes, it shows a
 * slow or hung command while it is still running.
 *
 * <p>Commands are tracked by their driver request id with {@link #start} and {@link #finish}. The
 * driver reports every started command as either succeeded or failed, so each start is finished,
 * unless the client closes while the command runs; {@link #clear} covers that case.
 */
public final class RunningDurationMetric implements Metric {
  private final String name;
  private final LongSupplier nanoTime;
  private final ConcurrentMap<Integer, Long> startNanosByRequestId = new ConcurrentHashMap<>();

  public RunningDurationMetric(final String name) {
    this(name, System::nanoTime);
  }

  @VisibleForTesting(otherwise = VisibleForTesting.AccessModifier.PRIVATE)
  public RunningDurationMetric(final String name, final LongSupplier nanoTime) {
    this.name = name;
    this.nanoTime = nanoTime;
  }

  public void start(final int requestId) {
    startNanosByRequestId.put(requestId, nanoTime.getAsLong());
  }

  public void finish(final int requestId) {
    startNanosByRequestId.remove(requestId);
  }

  /**
   * Forgets every running command. A command still running when its client closes may never be
   * reported as finished, so this is called when the task's statistics are closed.
   */
  public void clear() {
    startNanosByRequestId.clear();
  }

  long runningMs() {
    long now = nanoTime.getAsLong();
    long oldestStart = now;
    for (long start : startNanosByRequestId.values()) {
      oldestStart = Math.min(oldestStart, start);
    }
    return TimeUnit.NANOSECONDS.toMillis(now - oldestStart);
  }

  /** Not supported: the value comes from {@link #start} and {@link #finish}. */
  @Override
  public void sample(final long v) {
    throw new UnsupportedOperationException("Use start and finish");
  }

  @Override
  public void emit(final Consumer<MetricValue> consumer) {
    // Combined as the larger value: the oldest command running in either phase.
    consumer.accept(new MetricValue.MaxMetricValue(name, this::runningMs));
  }
}
