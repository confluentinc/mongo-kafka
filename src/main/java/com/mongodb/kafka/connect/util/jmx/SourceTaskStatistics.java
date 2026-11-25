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

import java.util.HashMap;
import java.util.Map;

import com.mongodb.lang.Nullable;

import com.mongodb.kafka.connect.util.VisibleForTesting;
import com.mongodb.kafka.connect.util.jmx.internal.Metric;
import com.mongodb.kafka.connect.util.jmx.internal.MongoMBean;
import com.mongodb.kafka.connect.util.jmx.internal.RunningDurationMetric;

public class SourceTaskStatistics extends MongoMBean {

  public static final Map<String, String> DESCRIPTIONS = new HashMap<>();

  static {
    DESCRIPTIONS.put(
        "records",
        "The total number of records a MongoDB source task passed to the Kafka Connect framework.");
    DESCRIPTIONS.put(
        "records-filtered",
        "The number of records a MongoDB source task passed to the Kafka connect framework that were then filtered by the framework. A filtered record is not written to Kafka.");
    DESCRIPTIONS.put(
        "records-acknowledged",
        "The total number of records a MongoDB source task passed to the Kafka Connect framework that were then successfully written to Kafka.");
    DESCRIPTIONS.put(
        "mongodb-bytes-read",
        "The total number of bytes a MongoDB source task read from the MongoDB server.");
    DESCRIPTIONS.put(
        "latest-mongodb-time-difference-secs",
        "The number of seconds of the most recent time difference recorded between a MongoDB server and the post-batch resume token held by a MongoDB source task. This value is calculated by subtracting the timestamp of the task's post-batch resume token from the operationTime value of the most recent successful MongoDB command executed by the task.");
    DESCRIPTIONS.put(
        "in-task-poll",
        "The total number of times the Kafka Connect framework executed the poll() method of a MongoDB source task.");
    DESCRIPTIONS.put(
        "in-task-poll-duration-ms",
        "The total number of milliseconds the Kafka Connect framework spent executing the poll() method of a MongoDB source task.");

    DESCRIPTIONS.put(
        "in-task-poll-duration-over-1-ms",
        "The total number of MongoDB source task poll() method executions with a duration that exceeded 1ms.");
    DESCRIPTIONS.put(
        "in-task-poll-duration-over-10-ms",
        "The total number of MongoDB source task poll() method executions with a duration that exceeded 10ms.");
    DESCRIPTIONS.put(
        "in-task-poll-duration-over-100-ms",
        "The total number of MongoDB source task poll() method executions with a duration that exceeded 100ms.");
    DESCRIPTIONS.put(
        "in-task-poll-duration-over-1000-ms",
        "The total number of MongoDB source task poll() method executions with a duration that exceeded 1000ms.");
    DESCRIPTIONS.put(
        "in-task-poll-duration-over-10000-ms",
        "The total number of MongoDB source task poll() method executions with a duration that exceeded 10000ms.");

    DESCRIPTIONS.put(
        "in-connect-framework",
        "The total number of times code in the Kafka Connect framework executed after the first invocation of the poll() method of the MongoDB source task.");
    DESCRIPTIONS.put(
        "in-connect-framework-duration-ms",
        "The total number of milliseconds spent executing code in the Kafka Connect framework since the framework first invoked the poll() method of the MongoDB source task. This metric does not count time executing code in the MongoDB sink task towards the total.");

    DESCRIPTIONS.put(
        "in-connect-framework-duration-over-1-ms",
        "The total number of times code in the Kafka Connect framework executed for a duration that exceeded 1ms.");
    DESCRIPTIONS.put(
        "in-connect-framework-duration-over-10-ms",
        "The total number of times code in the Kafka Connect framework executed for a duration that exceeded 10ms.");
    DESCRIPTIONS.put(
        "in-connect-framework-duration-over-100-ms",
        "The total number of times code in the Kafka Connect framework executed for a duration that exceeded 100ms.");
    DESCRIPTIONS.put(
        "in-connect-framework-duration-over-1000-ms",
        "The total number of times code in the Kafka Connect framework executed for a duration that exceeded 1000ms.");
    DESCRIPTIONS.put(
        "in-connect-framework-duration-over-10000-ms",
        "The total number of times code in the Kafka Connect framework executed for a duration that exceeded 10000ms.");

    DESCRIPTIONS.put(
        "initial-commands-successful",
        "The total number of initial commands issued by a MongoDB source task that succeeded. An initial command is a find or aggregate command sent to a MongoDB server that retrieves the first set of documents in a cursor. A getMore command is not an initial command.");
    DESCRIPTIONS.put(
        "initial-commands-successful-duration-ms",
        "The total number of milliseconds a MongoDB source task spent executing initial commands that succeeded.");

    DESCRIPTIONS.put(
        "initial-commands-successful-duration-over-1-ms",
        "The total number of successful initial commands issued by a MongoDB source task with a duration that exceeded 1ms.");
    DESCRIPTIONS.put(
        "initial-commands-successful-duration-over-10-ms",
        "The total number of successful initial commands issued by a MongoDB source task with a duration that exceeded 10ms.");
    DESCRIPTIONS.put(
        "initial-commands-successful-duration-over-100-ms",
        "The total number of successful initial commands issued by a MongoDB source task with a duration that exceeded 100ms.");
    DESCRIPTIONS.put(
        "initial-commands-successful-duration-over-1000-ms",
        "The total number of successful initial commands issued by a MongoDB source task with a duration that exceeded 1000ms.");
    DESCRIPTIONS.put(
        "initial-commands-successful-duration-over-10000-ms",
        "The total number of successful initial commands issued by a MongoDB source task with a duration that exceeded 10000ms.");

    DESCRIPTIONS.put(
        "getmore-commands-successful",
        "The total number of getMore commands issued by a MongoDB source task that succeeded.");
    DESCRIPTIONS.put(
        "getmore-commands-successful-duration-ms",
        "The total number of milliseconds a MongoDB source task spent executing getMore commands that succeeded.");

    DESCRIPTIONS.put(
        "getmore-commands-successful-duration-over-1-ms",
        "The total number of successful getMore commands issued by a MongoDB source task with a duration that exceeded 1ms.");
    DESCRIPTIONS.put(
        "getmore-commands-successful-duration-over-10-ms",
        "The total number of successful getMore commands issued by a MongoDB source task with a duration that exceeded 10ms.");
    DESCRIPTIONS.put(
        "getmore-commands-successful-duration-over-100-ms",
        "The total number of successful getMore commands issued by a MongoDB source task with a duration that exceeded 100ms.");
    DESCRIPTIONS.put(
        "getmore-commands-successful-duration-over-1000-ms",
        "The total number of successful getMore commands issued by a MongoDB source task with a duration that exceeded 1000ms.");
    DESCRIPTIONS.put(
        "getmore-commands-successful-duration-over-10000-ms",
        "The total number of successful getMore commands issued by a MongoDB source task with a duration that exceeded 10000ms.");

    DESCRIPTIONS.put(
        "initial-commands-failed",
        "The total number of initial commands issued by a MongoDB source task that failed. An initial command is a find or aggregate command sent to a MongoDB server that retrieves the first set of documents in a cursor. A getMore command is not an initial command.");
    DESCRIPTIONS.put(
        "initial-commands-failed-duration-ms",
        "The total number of milliseconds a MongoDB source task spent unsuccessfully attempting to issue initial commands to the MongoDB server.");

    DESCRIPTIONS.put(
        "initial-commands-failed-duration-over-1-ms",
        "The total number of failed initial commands issued by a MongoDB source task with a duration that exceeded 1ms.");
    DESCRIPTIONS.put(
        "initial-commands-failed-duration-over-10-ms",
        "The total number of failed initial commands issued by a MongoDB source task with a duration that exceeded 10ms.");
    DESCRIPTIONS.put(
        "initial-commands-failed-duration-over-100-ms",
        "The total number of failed initial commands issued by a MongoDB source task with a duration that exceeded 100ms.");
    DESCRIPTIONS.put(
        "initial-commands-failed-duration-over-1000-ms",
        "The total number of failed initial commands issued by a MongoDB source task with a duration that exceeded 1000ms.");
    DESCRIPTIONS.put(
        "initial-commands-failed-duration-over-10000-ms",
        "The total number of failed initial commands issued by a MongoDB source task with a duration that exceeded 10000ms.");

    DESCRIPTIONS.put(
        "getmore-commands-failed",
        "The total number of getMore commands issued by a MongoDB source task that failed.");
    DESCRIPTIONS.put(
        "getmore-commands-failed-duration-ms",
        "The total number of milliseconds a MongoDB source task spent unsuccessfully attempting to issue getMore commands to the MongoDB server.");

    DESCRIPTIONS.put(
        "getmore-commands-failed-duration-over-1-ms",
        "The total number of failed getMore commands issued by a MongoDB source task with a duration that exceeded 1ms.");
    DESCRIPTIONS.put(
        "getmore-commands-failed-duration-over-10-ms",
        "The total number of failed getMore commands issued by a MongoDB source task with a duration that exceeded 10ms.");
    DESCRIPTIONS.put(
        "getmore-commands-failed-duration-over-100-ms",
        "The total number of failed getMore commands issued by a MongoDB source task with a duration that exceeded 100ms.");
    DESCRIPTIONS.put(
        "getmore-commands-failed-duration-over-1000-ms",
        "The total number of failed getMore commands issued by a MongoDB source task with a duration that exceeded 1000ms.");
    DESCRIPTIONS.put(
        "getmore-commands-failed-duration-over-10000-ms",
        "The total number of failed getMore commands issued by a MongoDB source task with a duration that exceeded 10000ms.");

    DESCRIPTIONS.put(
        "initial-command-running-duration-ms",
        "The number of milliseconds the oldest initial command issued by a MongoDB source task that has not finished yet has been running, or 0 if none is running. For a change stream, this is the time spent so far opening or resuming the cursor.");
    DESCRIPTIONS.put(
        "getmore-command-running-duration-ms",
        "The number of milliseconds the oldest getMore command issued by a MongoDB source task that has not finished yet has been running, or 0 if none is running.");
    DESCRIPTIONS.put(
        "initial-commands-read-timeout",
        "The total number of initial commands issued by a MongoDB source task that failed with a socket read timeout. Also counted in initial-commands-failed.");
    DESCRIPTIONS.put(
        "initial-commands-socket-error",
        "The total number of initial commands issued by a MongoDB source task that failed with a socket error other than a read timeout, for example a connection reset or closed by the server. Also counted in initial-commands-failed.");
    DESCRIPTIONS.put(
        "getmore-commands-read-timeout",
        "The total number of getMore commands issued by a MongoDB source task that failed with a socket read timeout. Also counted in getmore-commands-failed.");
    DESCRIPTIONS.put(
        "getmore-commands-socket-error",
        "The total number of getMore commands issued by a MongoDB source task that failed with a socket error other than a read timeout, for example a connection reset or closed by the server. Also counted in getmore-commands-failed.");

    putLongCommandDescriptions("initial-commands-successful", "successful initial commands");
    putLongCommandDescriptions("getmore-commands-successful", "successful getMore commands");
    putLongCommandDescriptions("initial-commands-failed", "failed initial commands");
    putLongCommandDescriptions("getmore-commands-failed", "failed getMore commands");

  }

  /** Describes the buckets that {@link #COMMAND_MS_LIMITS} adds above {@link #MS_LIMITS}. */
  private static void putLongCommandDescriptions(final String metric, final String commands) {
    for (long limit : COMMAND_MS_LIMITS) {
      if (limit > MS_LIMITS[MS_LIMITS.length - 1]) {
        DESCRIPTIONS.put(
            metric + "-duration-over-" + limit + "-ms",
            "The total number of "
                + commands
                + " issued by a MongoDB source task with a duration that exceeded "
                + limit
                + "ms.");
      }
    }
  }

  private final Metric records = registerTotal("records");
  private final Metric recordsFiltered = registerTotal("records-filtered");
  private final Metric recordsAcknowledged = registerTotal("records-acknowledged");
  private final Metric mongodbBytesRead = registerTotal("mongodb-bytes-read");
  private final Metric latestMongodbTimeDifferenceSecs =
      registerLatest("latest-mongodb-time-difference-secs");

  private final Metric inTaskPoll = registerMs("in-task-poll");
  private final Metric inConnectFramework = registerMs("in-connect-framework");
  private final Metric initialCommandsSuccessful =
      registerMs("initial-commands-successful", COMMAND_MS_LIMITS);
  private final Metric getmoreCommandsSuccessful =
      registerMs("getmore-commands-successful", COMMAND_MS_LIMITS);
  private final Metric initialCommandsFailed =
      registerMs("initial-commands-failed", COMMAND_MS_LIMITS);
  private final Metric getmoreCommandsFailed =
      registerMs("getmore-commands-failed", COMMAND_MS_LIMITS);
  private final Metric initialCommandsReadTimeout = registerTotal("initial-commands-read-timeout");
  private final Metric initialCommandsSocketError = registerTotal("initial-commands-socket-error");
  private final Metric getmoreCommandsReadTimeout = registerTotal("getmore-commands-read-timeout");
  private final Metric getmoreCommandsSocketError = registerTotal("getmore-commands-socket-error");
  private final RunningDurationMetric initialCommandRunning;
  private final RunningDurationMetric getmoreCommandRunning;

  public SourceTaskStatistics(final String name) {
    this(name, newInitialCommandRunning(), newGetmoreCommandRunning());
  }

  /** For tests that control the clock of the running command trackers. */
  @VisibleForTesting(otherwise = VisibleForTesting.AccessModifier.PRIVATE)
  public SourceTaskStatistics(
      final String name,
      final RunningDurationMetric initialCommandRunning,
      final RunningDurationMetric getmoreCommandRunning) {
    super(name);
    this.initialCommandRunning = register(initialCommandRunning);
    this.getmoreCommandRunning = register(getmoreCommandRunning);
  }

  private static RunningDurationMetric newInitialCommandRunning() {
    return new RunningDurationMetric("initial-command-running-duration-ms");
  }

  private static RunningDurationMetric newGetmoreCommandRunning() {
    return new RunningDurationMetric("getmore-command-running-duration-ms");
  }

  /** Records that a command started. Only initial (aggregate or find) and getMore are tracked. */
  public void commandStarted(final String commandName, final int requestId) {
    RunningDurationMetric running = runningCommands(commandName);
    if (running != null) {
      running.start(requestId);
    }
  }

  /**
   * Records that a command succeeded or failed. A command this statistics did not start, such as
   * one that started in the other phase, is ignored.
   */
  public void commandFinished(final String commandName, final int requestId) {
    RunningDurationMetric running = runningCommands(commandName);
    if (running != null) {
      running.finish(requestId);
    }
  }

  /** Forgets every running command, for when the task's statistics close. */
  public void clearRunningCommands() {
    initialCommandRunning.clear();
    getmoreCommandRunning.clear();
  }

  @Nullable
  private RunningDurationMetric runningCommands(final String commandName) {
    if ("getMore".equals(commandName)) {
      return getmoreCommandRunning;
    } else if ("aggregate".equals(commandName) || "find".equals(commandName)) {
      return initialCommandRunning;
    }
    return null;
  }

  public Metric getRecords() {
    return records;
  }

  public Metric getRecordsFiltered() {
    return recordsFiltered;
  }

  public Metric getRecordsAcknowledged() {
    return recordsAcknowledged;
  }

  public Metric getMongodbBytesRead() {
    return mongodbBytesRead;
  }

  public Metric getLatestMongodbTimeDifferenceSecs() {
    return latestMongodbTimeDifferenceSecs;
  }

  public Metric getInTaskPoll() {
    return inTaskPoll;
  }

  public Metric getInConnectFramework() {
    return inConnectFramework;
  }

  public Metric getInitialCommandsSuccessful() {
    return initialCommandsSuccessful;
  }

  public Metric getGetmoreCommandsSuccessful() {
    return getmoreCommandsSuccessful;
  }

  public Metric getInitialCommandsFailed() {
    return initialCommandsFailed;
  }

  public Metric getGetmoreCommandsFailed() {
    return getmoreCommandsFailed;
  }

  public Metric getInitialCommandsReadTimeout() {
    return initialCommandsReadTimeout;
  }

  public Metric getInitialCommandsSocketError() {
    return initialCommandsSocketError;
  }

  public Metric getGetmoreCommandsReadTimeout() {
    return getmoreCommandsReadTimeout;
  }

  public Metric getGetmoreCommandsSocketError() {
    return getmoreCommandsSocketError;
  }


  @Override
  protected String getDescription(final String name) {
    return DESCRIPTIONS.get(name);
  }
}
