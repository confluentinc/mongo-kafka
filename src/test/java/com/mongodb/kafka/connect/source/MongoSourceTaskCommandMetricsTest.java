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

package com.mongodb.kafka.connect.source;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.management.Attribute;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandSucceededEvent;

import com.mongodb.kafka.connect.util.jmx.SourceTaskStatistics;

class MongoSourceTaskCommandMetricsTest {
  private static final ConnectionDescription CONNECTION =
      new ConnectionDescription(new ServerId(new ClusterId(), new ServerAddress()));

  @Test
  @DisplayName("A long resume lands in the long buckets of the successful initial commands")
  void testSucceededLongResumeIsBucketed() throws Exception {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");

    MongoSourceTask.mongoCommandSucceeded(succeeded("aggregate", 27 * 60_000), statistics);
    MongoSourceTask.mongoCommandSucceeded(succeeded("getMore", 1_014), statistics);

    assertEquals(1L, value(statistics, "initial-commands-successful-duration-over-300000-ms"));
    assertEquals(0L, value(statistics, "initial-commands-successful-duration-over-1800000-ms"));
    assertEquals(1L, value(statistics, "getmore-commands-successful-duration-over-1000-ms"));
    assertEquals(0L, value(statistics, "getmore-commands-successful-duration-over-10000-ms"));
  }

  @Test
  @DisplayName("A failed command, such as a read timeout, lands in the failed buckets")
  void testFailedCommandIsBucketed() throws Exception {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");
    MongoSocketReadTimeoutException timeout =
        new MongoSocketReadTimeoutException("timed out", new ServerAddress(), null);

    MongoSourceTask.mongoCommandFailed(failed("aggregate", 90_000, timeout), statistics);

    assertEquals(1L, value(statistics, "initial-commands-failed"));
    assertEquals(1L, value(statistics, "initial-commands-failed-duration-over-60000-ms"));
    assertEquals(0L, value(statistics, "initial-commands-failed-duration-over-300000-ms"));
  }

  @Test
  @DisplayName("Other commands are not counted")
  void testOtherCommandsAreIgnored() throws Exception {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");

    MongoSourceTask.mongoCommandSucceeded(succeeded("hello", 3), statistics);

    assertEquals(0L, value(statistics, "initial-commands-successful"));
    assertEquals(0L, value(statistics, "getmore-commands-successful"));
  }

  @Test
  @DisplayName("Batch size in KB is rounded up and ignores non-raw documents")
  void testBatchSizeKb() {
    RawBsonDocument small = RawBsonDocument.parse("{a: 1}");
    int smallBytes = small.getByteBuffer().limit();

    assertEquals(0, StartedMongoSourceTask.batchSizeKb(Collections.emptyList()));
    assertEquals(1, StartedMongoSourceTask.batchSizeKb(asList(small, small)));
    assertEquals(
        0, StartedMongoSourceTask.batchSizeKb(asList(new BsonDocument("a", new BsonInt32(1)))));

    int count = 1024 / smallBytes + 1;
    BsonDocument[] docs = new BsonDocument[count];
    Arrays.fill(docs, small);
    assertEquals(2, StartedMongoSourceTask.batchSizeKb(asList(docs)));
  }

  private static CommandSucceededEvent succeeded(final String command, final long elapsedMs) {
    return new CommandSucceededEvent(
        null,
        1,
        CONNECTION,
        command,
        new BsonDocument("ok", new BsonInt32(1)),
        TimeUnit.MILLISECONDS.toNanos(elapsedMs));
  }

  private static CommandFailedEvent failed(
      final String command, final long elapsedMs, final Throwable throwable) {
    return new CommandFailedEvent(
        null, 1, CONNECTION, command, TimeUnit.MILLISECONDS.toNanos(elapsedMs), throwable);
  }

  private static long value(final SourceTaskStatistics statistics, final String name)
      throws Exception {
    return (Long) ((Attribute) statistics.getAttribute(name)).getValue();
  }
}
