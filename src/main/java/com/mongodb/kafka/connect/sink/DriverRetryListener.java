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

import static com.mongodb.kafka.connect.sink.MongoSinkTask.LOGGER;

import static java.util.Arrays.asList;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.lang.Nullable;

/**
 * Logs the driver's own retries of the task's writes, which the task does not see otherwise. The
 * driver resends a retryable write that failed once, at once, with the same session and
 * transaction number. Each failed write attempt and each retry is logged at DEBUG, and a retry
 * that succeeded once at INFO, with how long the failed attempt took. Only command names, error
 * types and codes, and times are logged, never a command, which holds the records.
 */
final class DriverRetryListener implements CommandListener {
  private static final Set<String> WRITE_COMMANDS =
      new HashSet<>(asList("insert", "update", "delete"));

  private int requestId = -1;
  @Nullable private String retryableWriteId;
  @Nullable private String failedAttemptError;
  private long failedAttemptMs;
  private boolean retrying;

  @Override
  public synchronized void commandStarted(final CommandStartedEvent event) {
    if (!WRITE_COMMANDS.contains(event.getCommandName())) {
      return;
    }
    String writeId = retryableWriteId(event.getCommand());
    retrying = failedAttemptError != null && writeId != null && writeId.equals(retryableWriteId);
    if (retrying) {
      LOGGER.debug(
          "Driver retrying a MongoDB {} that failed after {} ms ({})",
          event.getCommandName(),
          failedAttemptMs,
          failedAttemptError);
    } else {
      failedAttemptError = null;
    }
    requestId = event.getRequestId();
    retryableWriteId = writeId;
  }

  @Override
  public synchronized void commandSucceeded(final CommandSucceededEvent event) {
    if (event.getRequestId() != requestId) {
      return;
    }
    if (retrying) {
      LOGGER.info(
          "MongoDB {} succeeded after a driver retry: the first attempt failed after {} ms ({}),"
              + " the retry took {} ms",
          event.getCommandName(),
          failedAttemptMs,
          failedAttemptError,
          event.getElapsedTime(TimeUnit.MILLISECONDS));
    }
    failedAttemptError = null;
    retrying = false;
  }

  @Override
  public synchronized void commandFailed(final CommandFailedEvent event) {
    if (event.getRequestId() != requestId) {
      return;
    }
    long elapsedMs = event.getElapsedTime(TimeUnit.MILLISECONDS);
    String error = StartedMongoSinkTask.errorSummary(event.getThrowable());
    if (retrying) {
      LOGGER.debug(
          "Driver retry of a MongoDB {} failed after {} ms ({})",
          event.getCommandName(),
          elapsedMs,
          error);
      failedAttemptError = null;
      retrying = false;
    } else {
      LOGGER.debug("MongoDB {} failed after {} ms ({})", event.getCommandName(), elapsedMs, error);
      failedAttemptMs = elapsedMs;
      failedAttemptError = error;
    }
  }

  /**
   * Identifies a retryable write, which the driver retries with the same session and transaction
   * number. Read during the event, since the command can be a view of a buffer the driver reuses.
   */
  @Nullable
  private static String retryableWriteId(final BsonDocument command) {
    BsonValue session = command.get("lsid");
    BsonValue txnNumber = command.get("txnNumber");
    return session == null || txnNumber == null ? null : session.toString() + txnNumber;
  }
}
