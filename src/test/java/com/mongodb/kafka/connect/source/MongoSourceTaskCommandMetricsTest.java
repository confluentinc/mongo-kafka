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
import java.util.concurrent.atomic.AtomicLong;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;

import com.mongodb.kafka.connect.source.statistics.StatisticsManager;
import com.mongodb.kafka.connect.util.jmx.SourceTaskStatistics;
import com.mongodb.kafka.connect.util.jmx.internal.RunningDurationMetric;

class MongoSourceTaskCommandMetricsTest {
  private static final String INITIAL_RUNNING = "initial-command-running-duration-ms";
  private static final String GETMORE_RUNNING = "getmore-command-running-duration-ms";
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
  @DisplayName("A read timeout and other socket errors are counted separately, never both")
  void testSocketErrorsAreClassified() throws Exception {
    SourceTaskStatistics statistics = new SourceTaskStatistics("test");
    ServerAddress server = new ServerAddress();

    MongoSourceTask.mongoCommandFailed(
        failed("getMore", 16_000, new MongoSocketReadTimeoutException("timed out", server, null)),
        statistics);
    MongoSourceTask.mongoCommandFailed(
        failed("getMore", 5, new MongoSocketReadException("closed", server)), statistics);
    MongoSourceTask.mongoCommandFailed(
        failed("aggregate", 3, new MongoSocketReadException("reset", server)), statistics);
    MongoSourceTask.mongoCommandFailed(
        failed(
            "aggregate",
            3,
            new MongoCommandException(new BsonDocument("code", new BsonInt32(13)), server)),
        statistics);

    assertEquals(2L, value(statistics, "getmore-commands-failed"));
    assertEquals(1L, value(statistics, "getmore-commands-read-timeout"));
    assertEquals(1L, value(statistics, "getmore-commands-socket-error"));
    assertEquals(2L, value(statistics, "initial-commands-failed"));
    assertEquals(0L, value(statistics, "initial-commands-read-timeout"));
    assertEquals(1L, value(statistics, "initial-commands-socket-error"));
  }

  @Test
  @DisplayName("A command counts as running from its start until it succeeds or fails")
  void testRunningCommandIsFinished() throws Exception {
    TwoPhaseStatistics manager = new TwoPhaseStatistics(false);
    CommandListener listener = MongoSourceTask.statisticsCommandListener(manager);
    listener.commandStarted(started("aggregate", 1));
    listener.commandStarted(started("getMore", 2));
    listener.commandStarted(started("hello", 3));
    manager.advanceMs(500);

    assertEquals(500L, value(manager.stream, INITIAL_RUNNING));
    assertEquals(500L, value(manager.stream, GETMORE_RUNNING));

    listener.commandSucceeded(succeeded("aggregate", 1, 10));
    listener.commandFailed(
        failed("getMore", 2, 10, new MongoSocketReadException("closed", new ServerAddress())));

    assertEquals(0L, value(manager.stream, INITIAL_RUNNING));
    assertEquals(0L, value(manager.stream, GETMORE_RUNNING));
  }

  @Test
  @DisplayName("A command running in one phase is not shown on the other phase's statistics")
  void testRunningCommandStaysInItsPhase() throws Exception {
    TwoPhaseStatistics manager = new TwoPhaseStatistics(false);
    CommandListener listener = MongoSourceTask.statisticsCommandListener(manager);
    listener.commandStarted(started("getMore", 1));
    manager.advanceMs(500);

    assertEquals(500L, value(manager.stream, GETMORE_RUNNING));
    assertEquals(0L, value(manager.copy, GETMORE_RUNNING));
  }

  @Test
  @DisplayName("A command started in the copy phase finishes after the switch to the stream phase")
  void testRunningCommandSurvivesStatisticsSwitch() throws Exception {
    TwoPhaseStatistics manager = new TwoPhaseStatistics(true);
    CommandListener listener = MongoSourceTask.statisticsCommandListener(manager);
    listener.commandStarted(started("aggregate", 1));
    manager.advanceMs(500);
    assertEquals(500L, value(manager.copy, INITIAL_RUNNING));

    manager.switchToStreamStatistics();
    listener.commandSucceeded(succeeded("aggregate", 1, 10));

    assertEquals(0L, value(manager.copy, INITIAL_RUNNING));
    assertEquals(0L, value(manager.stream, INITIAL_RUNNING));
    assertEquals(1L, value(manager.stream, "initial-commands-successful"));
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

  private static CommandStartedEvent started(final String command, final int requestId) {
    return new CommandStartedEvent(
        null, requestId, CONNECTION, "db", command, new BsonDocument(command, new BsonInt32(1)));
  }

  private static CommandSucceededEvent succeeded(final String command, final long elapsedMs) {
    return succeeded(command, 1, elapsedMs);
  }

  private static CommandSucceededEvent succeeded(
      final String command, final int requestId, final long elapsedMs) {
    return new CommandSucceededEvent(
        null,
        requestId,
        CONNECTION,
        command,
        new BsonDocument("ok", new BsonInt32(1)),
        TimeUnit.MILLISECONDS.toNanos(elapsedMs));
  }

  private static CommandFailedEvent failed(
      final String command, final long elapsedMs, final Throwable throwable) {
    return failed(command, 1, elapsedMs, throwable);
  }

  private static CommandFailedEvent failed(
      final String command, final int requestId, final long elapsedMs, final Throwable throwable) {
    return new CommandFailedEvent(
        null, requestId, CONNECTION, command, TimeUnit.MILLISECONDS.toNanos(elapsedMs), throwable);
  }

  private static long value(final SourceTaskStatistics statistics, final String name)
      throws Exception {
    return (Long) statistics.getAttribute(name);
  }

  /** Copy and stream statistics on a test clock, finishing commands the way the JMX one does. */
  private static final class TwoPhaseStatistics implements StatisticsManager {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final SourceTaskStatistics copy = statistics("copy");
    private final SourceTaskStatistics stream = statistics("stream");
    private volatile SourceTaskStatistics current;

    TwoPhaseStatistics(final boolean startWithCopy) {
      current = startWithCopy ? copy : stream;
    }

    private SourceTaskStatistics statistics(final String name) {
      return new SourceTaskStatistics(
          name,
          new RunningDurationMetric(INITIAL_RUNNING, nanos::get),
          new RunningDurationMetric(GETMORE_RUNNING, nanos::get));
    }

    void advanceMs(final long ms) {
      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    @Override
    public SourceTaskStatistics currentStatistics() {
      return current;
    }

    @Override
    public void switchToStreamStatistics() {
      current = stream;
    }

    @Override
    public void commandFinished(final String commandName, final int requestId) {
      copy.commandFinished(commandName, requestId);
      stream.commandFinished(commandName, requestId);
    }

    @Override
    public void setDNDTask(final boolean exempt) {}

    @Override
    public void close() {}
  }
}
