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

package com.mongodb.kafka.connect.util.jmx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.management.Attribute;
import javax.management.MBeanAttributeInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceTaskStatisticsTest {

  @Test
  @DisplayName("Every attribute has a description and every description has an attribute")
  void testDescriptionsMatchAttributes() {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");
    Set<String> attributes =
        Arrays.stream(statistics.getMBeanInfo().getAttributes())
            .map(MBeanAttributeInfo::getName)
            .collect(Collectors.toSet());

    assertEquals(SourceTaskStatistics.DESCRIPTIONS.keySet(), attributes);
  }

  @Test
  @DisplayName("Command duration buckets go up to 30 minutes")
  void testCommandBucketsCoverLongCommands() throws Exception {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");
    statistics.getInitialCommandsSuccessful().sample(27 * 60_000L);
    statistics.getGetmoreCommandsFailed().sample(90_000L);

    assertEquals(1L, value(statistics, "initial-commands-successful-duration-over-300000-ms"));
    assertEquals(
        0L, value(statistics, "initial-commands-successful-duration-over-1800000-ms"));
    assertEquals(1L, value(statistics, "getmore-commands-failed-duration-over-60000-ms"));
    assertEquals(0L, value(statistics, "getmore-commands-failed-duration-over-300000-ms"));
  }

  @Test
  @DisplayName("Poll metrics keep the original buckets")
  void testPollMetricsKeepOriginalBuckets() {
    Set<String> attributes =
        Arrays.stream(new SourceTaskStatistics("test").getMBeanInfo().getAttributes())
            .map(MBeanAttributeInfo::getName)
            .collect(Collectors.toSet());

    assertTrue(attributes.contains("in-task-poll-duration-over-10000-ms"));
    assertTrue(attributes.stream().noneMatch(a -> a.startsWith("in-task-poll-duration-over-6")));
  }

  @Test
  @DisplayName("Latest command durations read 0 until sampled, then hold the last sample")
  void testLatestCommandDurations() throws Exception {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");
    assertEquals(0L, value(statistics, "latest-initial-command-duration-ms"));
    assertEquals(0L, value(statistics, "latest-getmore-command-duration-ms"));

    statistics.getLatestInitialCommandDurationMs().sample(31_000L);
    statistics.getLatestInitialCommandDurationMs().sample(1_500L);
    statistics.getLatestGetmoreCommandDurationMs().sample(5_002L);

    assertEquals(1_500L, value(statistics, "latest-initial-command-duration-ms"));
    assertEquals(5_002L, value(statistics, "latest-getmore-command-duration-ms"));
  }

  private static long value(final SourceTaskStatistics statistics, final String name)
      throws Exception {
    return (Long) ((Attribute) statistics.getAttribute(name)).getValue();
  }
}
