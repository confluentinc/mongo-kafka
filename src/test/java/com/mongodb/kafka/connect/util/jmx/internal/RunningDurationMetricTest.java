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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunningDurationMetricTest {
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final RunningDurationMetric metric = new RunningDurationMetric("running", nanos::get);

  @Test
  @DisplayName("Reads 0 when no command is running")
  void testZeroWhenIdle() {
    assertEquals(0, metric.runningMs());
    metric.start(1);
    metric.finish(1);
    advanceMs(5_000);
    assertEquals(0, metric.runningMs());
  }

  @Test
  @DisplayName("Grows while a command runs, from the oldest running command")
  void testOldestRunningCommand() {
    metric.start(1);
    advanceMs(60_000);
    metric.start(2);
    advanceMs(1_000);

    assertEquals(61_000, metric.runningMs());

    metric.finish(1);
    assertEquals(1_000, metric.runningMs());
  }

  @Test
  @DisplayName("Finishing an unknown request is ignored")
  void testFinishUnknownRequest() {
    metric.start(1);
    advanceMs(10);
    metric.finish(99);
    assertEquals(10, metric.runningMs());
  }

  @Test
  @DisplayName("Emits its value under its name and does not accept samples")
  void testEmitAndSample() {
    metric.start(1);
    advanceMs(250);
    metric.emit(
        value -> {
          assertEquals("running", value.getName());
          assertEquals(250L, value.get());
        });
    assertThrows(UnsupportedOperationException.class, () -> metric.sample(1));
  }

  private void advanceMs(final long ms) {
    nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
  }
}
