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
package com.mongodb.kafka.connect.sink;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Level;
import org.junit.jupiter.api.Test;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.lang.Nullable;

final class DriverRetryListenerTest {
  private static final ConnectionDescription CONNECTION =
      new ConnectionDescription(new ServerId(new ClusterId(), new ServerAddress()));
  private static final BsonDocument SESSION =
      BsonDocument.parse("{id: {$binary: {base64: 'AAAAAAAAAAAAAAAAAAAAAA==', subType: '04'}}}");
  private static final String TIMEOUT = "MongoSocketReadTimeoutException code -2";

  private final DriverRetryListener listener = new DriverRetryListener();

  @Test
  void logsOnceWhenADriverRetrySucceeded() {
    try (CapturedLogs logs = new CapturedLogs()) {
      started(1, "insert", 7L);
      failed(1, "insert", 90_000);
      started(2, "insert", 7L);
      succeeded(2, "insert", 1_200);
      assertEquals(
          singletonList(
              "MongoDB insert succeeded after a driver retry: the first attempt failed after"
                  + " 90000 ms ("
                  + TIMEOUT
                  + "), the retry took 1200 ms"),
          logs.at(Level.INFO));
      assertEquals(
          asList(
              "MongoDB insert failed after 90000 ms (" + TIMEOUT + ")",
              "Driver retrying a MongoDB insert that failed after 90000 ms (" + TIMEOUT + ")"),
          logs.at(Level.DEBUG));
    }
  }

  @Test
  void logsNothingForAWriteThatSucceededAtOnce() {
    try (CapturedLogs logs = new CapturedLogs()) {
      started(1, "update", 7L);
      succeeded(1, "update", 5);
      assertTrue(logs.at(Level.INFO).isEmpty());
      assertTrue(logs.at(Level.DEBUG).isEmpty());
    }
  }

  @Test
  void logsAFailedRetryOnlyAtDebug() {
    try (CapturedLogs logs = new CapturedLogs()) {
      started(1, "update", 7L);
      failed(1, "update", 90_000);
      started(2, "update", 7L);
      failed(2, "update", 90_000);
      assertTrue(logs.at(Level.INFO).isEmpty());
      assertEquals(
          "Driver retry of a MongoDB update failed after 90000 ms (" + TIMEOUT + ")",
          logs.at(Level.DEBUG).get(2));
    }
  }

  @Test
  void doesNotTakeTheNextWriteForARetry() {
    assertNoRetryLogged(7L, 8L);
  }

  @Test
  void doesNotTakeAWriteWithoutATransactionNumberForARetry() {
    assertNoRetryLogged(null, null);
  }

  /** A write fails, and the next write, with {@code nextTxnNumber}, succeeds. */
  private void assertNoRetryLogged(
      @Nullable final Long txnNumber, @Nullable final Long nextTxnNumber) {
    try (CapturedLogs logs = new CapturedLogs()) {
      started(1, "insert", txnNumber);
      failed(1, "insert", 90_000);
      started(2, "insert", nextTxnNumber);
      succeeded(2, "insert", 5);
      assertTrue(logs.at(Level.INFO).isEmpty(), logs.at(Level.INFO)::toString);
      assertEquals(1, logs.at(Level.DEBUG).size());
    }
  }

  @Test
  void ignoresCommandsOtherThanWrites() {
    try (CapturedLogs logs = new CapturedLogs()) {
      started(1, "listCollections", null);
      failed(1, "listCollections", 90_000);
      assertTrue(logs.at(Level.DEBUG).isEmpty());
    }
  }

  @Test
  void neverLogsTheCommand() {
    try (CapturedLogs logs = new CapturedLogs()) {
      started(1, "insert", 7L);
      failed(1, "insert", 90_000);
      started(2, "insert", 7L);
      succeeded(2, "insert", 5);
      List<String> messages = new ArrayList<>(logs.at(Level.DEBUG));
      messages.addAll(logs.at(Level.INFO));
      assertEquals(3, messages.size());
      messages.forEach(message -> assertFalse(message.contains("value-1234"), message));
    }
  }

  private void started(
      final int requestId, final String commandName, @Nullable final Long txnNumber) {
    BsonDocument command =
        new BsonDocument(commandName, new BsonString("myColl")).append("lsid", SESSION);
    if (txnNumber != null) {
      command.append("txnNumber", new BsonInt64(txnNumber));
    }
    command.append(
        "documents", new BsonArray(singletonList(BsonDocument.parse("{secret: 'value-1234'}"))));
    listener.commandStarted(
        new CommandStartedEvent(requestId, CONNECTION, "myDb", commandName, command));
  }

  private void failed(final int requestId, final String commandName, final long elapsedMs) {
    listener.commandFailed(
        new CommandFailedEvent(
            requestId,
            CONNECTION,
            commandName,
            TimeUnit.MILLISECONDS.toNanos(elapsedMs),
            new MongoSocketReadTimeoutException(
                "read timed out", new ServerAddress(), new RuntimeException())));
  }

  private void succeeded(final int requestId, final String commandName, final long elapsedMs) {
    listener.commandSucceeded(
        new CommandSucceededEvent(
            requestId,
            CONNECTION,
            commandName,
            new BsonDocument("ok", new BsonInt32(1)),
            TimeUnit.MILLISECONDS.toNanos(elapsedMs)));
  }
}
