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

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonTimestamp;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.ServerCursor;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.changestream.FullDocumentBeforeChange;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;

import com.mongodb.kafka.connect.source.MongoSourceConfig.StartupConfig.StartupMode;
import com.mongodb.kafka.connect.source.statistics.JmxStatisticsManager;
import com.mongodb.kafka.connect.util.jmx.SourceTaskStatistics;

final class StartedMongoSourceTaskTest {
  @Nested
  final class ChangeStreamIterableOptionsTest {
    private final Map<String, String> properties = new HashMap<>();
    private StartedMongoSourceTask task;

    @BeforeEach
    void setUp() {
      properties.clear();
    }

    @AfterEach
    void tearDown() {
      if (task != null) {
        task.close();
      }
    }

    @Test
    void fullDocumentBeforeChange() {
      FullDocumentBeforeChange expected = FullDocumentBeforeChange.WHEN_AVAILABLE;
      properties.put(MongoSourceConfig.FULL_DOCUMENT_BEFORE_CHANGE_CONFIG, expected.getValue());
      MongoSourceConfig cfg = new MongoSourceConfig(properties);
      SourceTaskContext context = mock(SourceTaskContext.class);
      OffsetStorageReader offsetStorageReader = mock(OffsetStorageReader.class);
      when(offsetStorageReader.offset(any())).thenReturn(emptyMap());
      when(context.offsetStorageReader()).thenReturn(offsetStorageReader);
      ChangeStreamIterable<?> changeStreamIterable = cast(mock(ChangeStreamIterable.class));
      MongoClient client = mock(MongoClient.class);
      when(changeStreamIterable.withDocumentClass(any())).thenReturn(cast(changeStreamIterable));
      when(changeStreamIterable.cursor()).thenReturn(cast(mock(MongoChangeStreamCursor.class)));
      when(client.watch()).thenReturn(cast(changeStreamIterable));
      task =
          new StartedMongoSourceTask(
              () -> context, cfg, client, null, new JmxStatisticsManager(false, "unknown"));
      task.poll();
      ArgumentCaptor<FullDocumentBeforeChange> argCaptor =
          ArgumentCaptor.forClass(FullDocumentBeforeChange.class);
      verify(changeStreamIterable, atLeastOnce()).fullDocumentBeforeChange(argCaptor.capture());
      List<FullDocumentBeforeChange> capturedArgs = argCaptor.getAllValues();
      assertTrue(capturedArgs.stream().allMatch(v -> v.equals(expected)), capturedArgs::toString);
    }

    @Test
    void startAtOperationTime() {
      int expectedEpochSeconds = 123;
      BsonTimestamp expected = new BsonTimestamp(expectedEpochSeconds, 0);
      properties.put(MongoSourceConfig.STARTUP_MODE_CONFIG, StartupMode.TIMESTAMP.propertyValue());
      properties.put(
          MongoSourceConfig.STARTUP_MODE_TIMESTAMP_START_AT_OPERATION_TIME_CONFIG,
          String.valueOf(expectedEpochSeconds));
      MongoSourceConfig cfg = new MongoSourceConfig(properties);
      SourceTaskContext context = mock(SourceTaskContext.class);
      OffsetStorageReader offsetStorageReader = mock(OffsetStorageReader.class);
      when(offsetStorageReader.offset(any())).thenReturn(emptyMap());
      when(context.offsetStorageReader()).thenReturn(offsetStorageReader);
      ChangeStreamIterable<?> changeStreamIterable = cast(mock(ChangeStreamIterable.class));
      MongoClient client = mock(MongoClient.class);
      when(changeStreamIterable.withDocumentClass(any())).thenReturn(cast(changeStreamIterable));
      when(changeStreamIterable.cursor()).thenReturn(cast(mock(MongoChangeStreamCursor.class)));
      when(client.watch()).thenReturn(cast(changeStreamIterable));
      task =
          new StartedMongoSourceTask(
              () -> context, cfg, client, null, new JmxStatisticsManager(false, "unknown"));
      task.poll();
      ArgumentCaptor<BsonTimestamp> argCaptor = ArgumentCaptor.forClass(BsonTimestamp.class);
      verify(changeStreamIterable, atLeastOnce()).startAtOperationTime(argCaptor.capture());
      List<BsonTimestamp> capturedArgs = argCaptor.getAllValues();
      assertTrue(capturedArgs.stream().allMatch(v -> v.equals(expected)), capturedArgs::toString);
    }

    @Test
    void changeStreamFailureDoesNotLeakDriverErrorMessage() {
      // A change-stream cursor failure surfaces to the framework as a ConnectException; its message
      // and cause chain are logged to the shared connect log and the task-status trace. The raw
      // server error message can echo record-derived content, so it must not appear there.
      String canary = "SENSITIVE_CANARY_errmsg_9f3a1c";
      BsonDocument response =
          new BsonDocument("ok", new BsonInt32(0))
              .append("code", new BsonInt32(100))
              .append("codeName", new BsonString("CommandFailed"))
              .append("errmsg", new BsonString(canary));

      MongoSourceConfig cfg = new MongoSourceConfig(properties);
      SourceTaskContext context = mock(SourceTaskContext.class);
      OffsetStorageReader offsetStorageReader = mock(OffsetStorageReader.class);
      when(offsetStorageReader.offset(any())).thenReturn(emptyMap());
      when(context.offsetStorageReader()).thenReturn(offsetStorageReader);
      ChangeStreamIterable<?> changeStreamIterable = cast(mock(ChangeStreamIterable.class));
      MongoClient client = mock(MongoClient.class);
      when(changeStreamIterable.withDocumentClass(any())).thenReturn(cast(changeStreamIterable));
      when(changeStreamIterable.cursor())
          .thenThrow(new MongoCommandException(response, new ServerAddress("localhost", 27017)));
      when(client.watch()).thenReturn(cast(changeStreamIterable));

      ConnectException exc =
          assertThrows(
              ConnectException.class,
              () ->
                  new StartedMongoSourceTask(
                      () -> context,
                      cfg,
                      client,
                      null,
                      new JmxStatisticsManager(false, "unknown")));

      assertNull(exc.getCause(), "raw driver exception must not be chained to the framework");
      assertFalse(
          exc.getMessage().contains(canary),
          () -> "raw server error message leaked into the ConnectException: " + exc.getMessage());
      assertTrue(exc.getMessage().contains("code: 100"), exc.getMessage());
    }
  }

  static final class CursorFailureTest {
    private StartedMongoSourceTask task;

    @AfterEach
    void tearDown() {
      if (task != null) {
        task.close();
      }
    }

    @Test
    void failsTheTaskAfterMaxConsecutiveFailures() {
      Map<String, String> properties = new HashMap<>();
      properties.put(MongoSourceConfig.POLL_RETRY_MAX_ATTEMPTS_CONFIG, "3");
      properties.put(MongoSourceConfig.POLL_RETRY_BACKOFF_MS_CONFIG, "0");
      MongoChangeStreamCursor<?> cursor = cast(mock(MongoChangeStreamCursor.class));
      when(cursor.getServerCursor()).thenReturn(new ServerCursor(1, new ServerAddress()));
      when(cursor.tryNext())
          .thenThrow(
              new MongoSocketReadTimeoutException("timed out", new ServerAddress(), new IOException()));
      task = createTask(properties, cursor);

      task.poll();
      task.poll();
      assertThrows(ConnectException.class, () -> task.poll());
      verify(cursor, times(3)).tryNext();
    }

    @Test
    void doesNotFailTheTaskWhenErrorsAreTolerated() {
      Map<String, String> properties = new HashMap<>();
      properties.put(MongoSourceConfig.ERRORS_TOLERANCE_CONFIG, "all");
      properties.put(MongoSourceConfig.POLL_RETRY_MAX_ATTEMPTS_CONFIG, "2");
      properties.put(MongoSourceConfig.POLL_RETRY_BACKOFF_MS_CONFIG, "0");
      MongoChangeStreamCursor<?> cursor = cast(mock(MongoChangeStreamCursor.class));
      when(cursor.getServerCursor()).thenReturn(new ServerCursor(1, new ServerAddress()));
      when(cursor.tryNext())
          .thenThrow(
              new MongoSocketReadTimeoutException(
                  "timed out", new ServerAddress(), new IOException()));
      task = createTask(properties, cursor);

      for (int i = 0; i < 5; i++) {
        task.poll();
      }
      verify(cursor, times(5)).tryNext();
    }

    @Test
    void successfulReadResetsTheFailureCount() {
      Map<String, String> properties = new HashMap<>();
      properties.put(MongoSourceConfig.POLL_RETRY_MAX_ATTEMPTS_CONFIG, "2");
      properties.put(MongoSourceConfig.POLL_RETRY_BACKOFF_MS_CONFIG, "0");
      MongoChangeStreamCursor<?> cursor = cast(mock(MongoChangeStreamCursor.class));
      when(cursor.getServerCursor()).thenReturn(new ServerCursor(1, new ServerAddress()));
      MongoSocketReadTimeoutException failure =
          new MongoSocketReadTimeoutException("timed out", new ServerAddress(), new IOException());
      when(cursor.tryNext())
          .thenThrow(failure)
          .thenReturn(null)
          .thenThrow(failure)
          .thenReturn(null);
      task = createTask(properties, cursor);

      for (int i = 0; i < 4; i++) {
        task.poll();
      }
      verify(cursor, times(4)).tryNext();
    }

    private StartedMongoSourceTask createTask(
        final Map<String, String> properties, final MongoChangeStreamCursor<?> cursor) {
      MongoSourceConfig cfg = new MongoSourceConfig(properties);
      SourceTaskContext context = mock(SourceTaskContext.class);
      OffsetStorageReader offsetStorageReader = mock(OffsetStorageReader.class);
      when(offsetStorageReader.offset(any())).thenReturn(emptyMap());
      when(context.offsetStorageReader()).thenReturn(offsetStorageReader);
      ChangeStreamIterable<?> changeStreamIterable = cast(mock(ChangeStreamIterable.class));
      MongoClient client = mock(MongoClient.class);
      when(changeStreamIterable.withDocumentClass(any())).thenReturn(cast(changeStreamIterable));
      when(changeStreamIterable.cursor()).thenReturn(cast(cursor));
      when(client.watch()).thenReturn(cast(changeStreamIterable));
      return new StartedMongoSourceTask(
          () -> context, cfg, client, null, new JmxStatisticsManager(false, "unknown"));
    }
  }

  static final class CommandFailureTest {
    @Test
    void socketFailureOfGetMoreIsSampledAndDoesNotThrow() {
      CommandFailedEvent event =
          new CommandFailedEvent(
              1,
              new ConnectionDescription(new ServerId(new ClusterId(), new ServerAddress())),
              "getMore",
              TimeUnit.MILLISECONDS.toNanos(20000),
              new MongoSocketReadTimeoutException(
                  "timed out", new ServerAddress(), new IOException()));
      SourceTaskStatistics statistics = mock(SourceTaskStatistics.class, RETURNS_DEEP_STUBS);

      MongoSourceTask.mongoCommandFailed(event, statistics);

      verify(statistics.getGetmoreCommandsFailed()).sample(20000L);
    }
  }

  static final class BackoffTest {
    @Test
    void doublesUpToTheMaximum() {
      assertEquals(500, StartedMongoSourceTask.backoffMs(1, 1000, 30000, 0));
      assertEquals(1000, StartedMongoSourceTask.backoffMs(2, 1000, 30000, 0));
      assertEquals(8000, StartedMongoSourceTask.backoffMs(5, 1000, 30000, 0));
      assertEquals(15000, StartedMongoSourceTask.backoffMs(6, 1000, 30000, 0));
      assertEquals(15000, StartedMongoSourceTask.backoffMs(50, 1000, 30000, 0));
      assertEquals(15000, StartedMongoSourceTask.backoffMs(1000, 1000, 30000, 0));
    }

    @Test
    void jitterStaysWithinTheDelay() {
      long almostFull = StartedMongoSourceTask.backoffMs(3, 1000, 30000, 0.999999);
      assertTrue(almostFull >= 2000 && almostFull <= 4000, String.valueOf(almostFull));
    }

    @Test
    void zeroDisablesTheBackoff() {
      assertEquals(0, StartedMongoSourceTask.backoffMs(3, 0, 30000, 0.5));
      assertEquals(0, StartedMongoSourceTask.backoffMs(3, 1000, 0, 0.5));
    }

    @Test
    void initialAboveMaximumIsCapped() {
      assertEquals(1000, StartedMongoSourceTask.backoffMs(1, 5000, 2000, 0));
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T cast(final Object o) {
    return (T) o;
  }
}
