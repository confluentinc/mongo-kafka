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
 *
 * Original Work: Apache License, Version 2.0, Copyright 2017 Hans-Peter Grahsl.
 */
package com.mongodb.kafka.connect.sink;

import static com.mongodb.kafka.connect.sink.MongoSinkTask.LOGGER;
import static com.mongodb.kafka.connect.sink.MongoSinkTopicConfig.BULK_WRITE_ORDERED_CONFIG;
import static com.mongodb.kafka.connect.util.TimeseriesValidation.validateCollection;

import static java.util.Arrays.asList;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;

import org.bson.BsonDocument;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoConnectionPoolClearedException;
import com.mongodb.MongoException;
import com.mongodb.MongoNamespace;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.WriteModel;

import com.mongodb.kafka.connect.sink.dlq.AnalyzedBatchFailedWithBulkWriteException;
import com.mongodb.kafka.connect.sink.dlq.ErrorReporter;
import com.mongodb.kafka.connect.source.statistics.JmxStatisticsManager;
import com.mongodb.kafka.connect.util.VisibleForTesting;
import com.mongodb.kafka.connect.util.jmx.SinkTaskStatistics;
import com.mongodb.kafka.connect.util.jmx.internal.MBeanServerUtils;
import com.mongodb.kafka.connect.util.time.InnerOuterTimer;
import com.mongodb.kafka.connect.util.time.InnerOuterTimer.InnerTimer;
import com.mongodb.kafka.connect.util.time.Timer;

final class StartedMongoSinkTask implements AutoCloseable {
  /**
   * Server error codes for transient conditions (overload, failover, network, timeouts). A write
   * that fails only with these is retried; every other code is a terminal per-record error.
   */
  static final Set<Integer> TRANSIENT_ERROR_CODES =
      new HashSet<>(
          asList(
              6, // HostUnreachable
              7, // HostNotFound
              50, // MaxTimeMSExpired
              64, // WriteConcernFailed (wtimeout)
              89, // NetworkTimeout
              91, // ShutdownInProgress
              189, // PrimarySteppedDown
              262, // ExceededTimeLimit
              462, // IngressRequestRateLimitExceeded
              9001, // SocketException
              10107, // NotWritablePrimary
              11600, // InterruptedAtShutdown
              11602, // InterruptedDueToReplStateChange
              13435, // NotPrimaryNoSecondaryOk
              13436, // NotPrimaryOrSecondary
              16500 // Request rate too large
              ));

  private static final String RETRYABLE_WRITE_ERROR_LABEL = "RetryableWriteError";
  static final int MAX_CONSECUTIVE_RETRIES = 10;
  static final long RETRY_BACKOFF_BASE_MS = 1_000;
  static final long RETRY_BACKOFF_CAP_MS = 60_000;

  private final MongoSinkConfig sinkConfig;
  private final MongoClient mongoClient;
  private final ErrorReporter errorReporter;
  private final LongConsumer retryTimeout;
  private final Set<MongoNamespace> checkedTimeseriesNamespaces;
  private int consecutiveRetries;
  /**
   * Records of the current {@code put()} that are already written or reported to the DLQ. Kept
   * across a {@link RetriableException} so the redelivered {@code put()} skips them, and cleared
   * once a {@code put()} completes.
   */
  private final Set<RecordPosition> handledRecords = new HashSet<>();
  /**
   * How the records of the current {@code put()} were processed. Kept across a {@link
   * RetriableException} so a redelivered record is written with the same write model, including an
   * {@code _id} the id strategy generated. Resending a write whose outcome is unknown, such as one
   * that timed out, then replaces the document it may have written instead of adding another.
   * Cleared once a {@code put()} completes.
   */
  private final Map<RecordPosition, MongoProcessedSinkRecordData> processedRecords =
      new HashMap<>();

  private final SinkTaskStatistics statistics;
  private final InnerOuterTimer inTaskPutInConnectFrameworkTimer;

  StartedMongoSinkTask(
      final MongoSinkConfig sinkConfig,
      final MongoClient mongoClient,
      final ErrorReporter errorReporter) {
    this(sinkConfig, mongoClient, errorReporter, timeoutMs -> {});
  }

  /**
   * @param retryTimeout sets how long the framework waits before redelivering a batch after a
   *     {@link RetriableException}, see {@link
   *     org.apache.kafka.connect.sink.SinkTaskContext#timeout(long)}
   */
  StartedMongoSinkTask(
      final MongoSinkConfig sinkConfig,
      final MongoClient mongoClient,
      final ErrorReporter errorReporter,
      final LongConsumer retryTimeout) {
    this.sinkConfig = sinkConfig;
    this.mongoClient = mongoClient;
    this.errorReporter =
        (record, e) -> {
          errorReporter.report(record, e);
          handledRecords.add(new RecordPosition(record));
        };
    this.retryTimeout = retryTimeout;
    checkedTimeseriesNamespaces = new HashSet<>();
    statistics = new SinkTaskStatistics(getMBeanName());
    statistics.register();
    inTaskPutInConnectFrameworkTimer =
        InnerOuterTimer.start(
            (inTaskPutSample) -> {
              statistics.getInTaskPut().sample(inTaskPutSample.toMillis());
              if (LOGGER.isDebugEnabled()) {
                // toJSON relatively expensive
                LOGGER.debug(statistics.getName() + ": " + statistics.toJSON());
              }
            },
            (inFrameworkSample) ->
                statistics.getInConnectFramework().sample(inFrameworkSample.toMillis()));
  }

  private String getMBeanName() {
    String id = MBeanServerUtils.taskIdFromCurrentThread();
    String connectorName = JmxStatisticsManager.getConnectorName(this.sinkConfig.getOriginals());
    return "com.mongodb.kafka.connect:type=sink-task-metrics,connector="
        + connectorName
        + ",task=sink-task-"
        + id;
  }

  /** @see MongoSinkTask#stop() */
  @SuppressWarnings("try")
  @Override
  public void close() {
    try (MongoClient autoCloseable = mongoClient) {
      statistics.unregister();
    }
  }

  /** @see MongoSinkTask#put(Collection) */
  @SuppressWarnings("try")
  void put(final Collection<SinkRecord> records) {
    try (InnerTimer automatic = inTaskPutInConnectFrameworkTimer.sampleOuter()) {
      statistics.getRecords().sample(records.size());
      trackLatestRecordTimestampOffset(records);
      if (records.isEmpty()) {
        LOGGER.debug("No sink records to process for current poll operation");
      } else {
        Timer processingTime = Timer.start();
        List<List<MongoProcessedSinkRecordData>> batches =
            MongoSinkRecordProcessor.orderedGroupByTopicAndNamespace(
                withoutHandledRecords(records), this::process, errorReporter);
        statistics.getProcessingPhases().sample(processingTime.getElapsedTime().toMillis());
        for (List<MongoProcessedSinkRecordData> batch : batches) {
          bulkWriteBatch(batch);
        }
        consecutiveRetries = 0;
        handledRecords.clear();
        processedRecords.clear();
      }
    }
  }

  private MongoProcessedSinkRecordData process(final SinkRecord record) {
    return processedRecords.computeIfAbsent(
        new RecordPosition(record), position -> new MongoProcessedSinkRecordData(record, sinkConfig));
  }

  private Collection<SinkRecord> withoutHandledRecords(final Collection<SinkRecord> records) {
    if (handledRecords.isEmpty()) {
      return records;
    }
    return records.stream()
        .filter(record -> !handledRecords.contains(new RecordPosition(record)))
        .collect(Collectors.toList());
  }

  private void trackLatestRecordTimestampOffset(final Collection<SinkRecord> records) {
    OptionalLong latestRecord =
        records.stream()
            .filter(v -> v.timestamp() != null)
            .mapToLong(ConnectRecord::timestamp)
            .max();
    if (latestRecord.isPresent()) {
      long offsetMs = System.currentTimeMillis() - latestRecord.getAsLong();
      statistics.getLatestKafkaTimeDifferenceMs().sample(offsetMs);
    }
  }

  private void bulkWriteBatch(final List<MongoProcessedSinkRecordData> batch) {
    if (batch.isEmpty()) {
      return;
    }

    MongoNamespace namespace = batch.get(0).getNamespace();
    MongoSinkTopicConfig config = batch.get(0).getConfig();
    checkTimeseries(namespace, config);

    List<WriteModel<BsonDocument>> writeModels =
        batch.stream()
            .map(MongoProcessedSinkRecordData::getWriteModel)
            .collect(Collectors.toList());
    boolean bulkWriteOrdered = config.getBoolean(BULK_WRITE_ORDERED_CONFIG);

    Timer writeTime = Timer.start();
    try {
      LOGGER.debug(
          "Bulk writing {} document(s) into collection [{}] via an {} bulk write",
          writeModels.size(),
          namespace.getFullName(),
          bulkWriteOrdered ? "ordered" : "unordered");
      BulkWriteResult result =
          mongoClient
              .getDatabase(namespace.getDatabaseName())
              .getCollection(namespace.getCollectionName(), BsonDocument.class)
              .bulkWrite(writeModels, new BulkWriteOptions().ordered(bulkWriteOrdered));
      statistics.getBatchWritesSuccessful().sample(writeTime.getElapsedTime().toMillis());
      statistics.getRecordsSuccessful().sample(batch.size());
      LOGGER.debug("Mongodb bulk write result: {}", result);
      batch.forEach(record -> handledRecords.add(new RecordPosition(record.getSinkRecord())));
    } catch (RuntimeException e) {
      statistics.getBatchWritesFailed().sample(writeTime.getElapsedTime().toMillis());
      statistics.getRecordsFailed().sample(batch.size());
      if (e instanceof MongoBulkWriteException) {
        markAppliedWrites(batch, bulkWriteOrdered, (MongoBulkWriteException) e);
      }
      retryIfTransient(e);
      if (config.tolerateDataErrors() && !(e instanceof MongoBulkWriteException)) {
        LOGGER.trace("non Data Error, failing the connector", e);
        throw new DataException(
            "non Data Error, fail the connector. Exception type: " + e.getClass().getName());
      }
      handleTolerableWriteException(
          batch.stream()
              .map(MongoProcessedSinkRecordData::getSinkRecord)
              .collect(Collectors.toList()),
          bulkWriteOrdered,
          e,
          config.logErrors(),
          config.tolerateErrors() || config.tolerateDataErrors());
    }
    checkRateLimit(config);
  }

  /**
   * Marks the writes of a failed batch that the server applied. With a write concern error the
   * applied writes may not be durable, so they are left to be written again.
   */
  private void markAppliedWrites(
      final List<MongoProcessedSinkRecordData> batch,
      final boolean ordered,
      final MongoBulkWriteException e) {
    if (e.getWriteConcernError() != null) {
      return;
    }
    Set<Integer> failedIndices =
        e.getWriteErrors().stream().map(BulkWriteError::getIndex).collect(Collectors.toSet());
    int end = ordered ? failedIndices.stream().min(Integer::compare).orElse(0) : batch.size();
    for (int i = 0; i < end; i++) {
      if (!failedIndices.contains(i)) {
        handledRecords.add(new RecordPosition(batch.get(i).getSinkRecord()));
      }
    }
  }

  /**
   * Throws a {@link RetriableException} for a transient error, so the framework redelivers the
   * same records after a capped exponential backoff with full jitter instead of failing the task
   * or dead-lettering them. Once {@link #MAX_CONSECUTIVE_RETRIES} retries in a row have failed,
   * returns, and the error is handled as before.
   *
   * <p>The framework redelivers the whole {@code put()}; records that were already written or
   * reported to the DLQ are skipped then, see {@link #handledRecords}, and the rest are written
   * with the write models of the failed attempt, see {@link #processedRecords}. This matters
   * because with the default {@code BsonOidStrategy} a record without an {@code _id} gets a new one
   * every time it is processed, so processing it again would duplicate the document.
   */
  private void retryIfTransient(final RuntimeException e) {
    if (!isTransient(e) || consecutiveRetries >= MAX_CONSECUTIVE_RETRIES) {
      return;
    }
    consecutiveRetries++;
    long capMs =
        Math.min(RETRY_BACKOFF_CAP_MS, RETRY_BACKOFF_BASE_MS << Math.min(consecutiveRetries - 1, 16));
    // The framework ignores a timeout of 0 and waits for the next offset commit instead.
    long backoffMs = 1 + ThreadLocalRandom.current().nextLong(capMs);
    LOGGER.warn(
        "Transient error writing to MongoDB ({}), retry {} of {} in {} ms",
        errorSummary(e),
        consecutiveRetries,
        MAX_CONSECUTIVE_RETRIES,
        backoffMs);
    retryTimeout.accept(backoffMs);
    // Do not chain the driver exception: its message can carry record-derived content.
    throw new RetriableException("Transient error writing to MongoDB: " + errorSummary(e));
  }

  @VisibleForTesting(otherwise = VisibleForTesting.AccessModifier.PRIVATE)
  static boolean isTransient(final RuntimeException e) {
    if (e instanceof MongoBulkWriteException) {
      MongoBulkWriteException bulkWriteException = (MongoBulkWriteException) e;
      List<BulkWriteError> writeErrors = bulkWriteException.getWriteErrors();
      boolean writeErrorsTransient =
          writeErrors.stream().allMatch(error -> TRANSIENT_ERROR_CODES.contains(error.getCode()));
      boolean writeConcernErrorTransient =
          bulkWriteException.getWriteConcernError() == null
              || TRANSIENT_ERROR_CODES.contains(bulkWriteException.getWriteConcernError().getCode());
      boolean anyError = !writeErrors.isEmpty() || bulkWriteException.getWriteConcernError() != null;
      return anyError && writeErrorsTransient && writeConcernErrorTransient;
    }
    // The driver retries a cleared pool once but does not label it as a retryable write error.
    if (e instanceof MongoSocketException
        || e instanceof MongoTimeoutException
        || e instanceof MongoConnectionPoolClearedException) {
      return true;
    }
    if (e instanceof MongoException) {
      MongoException mongoException = (MongoException) e;
      return mongoException.hasErrorLabel(RETRYABLE_WRITE_ERROR_LABEL)
          || TRANSIENT_ERROR_CODES.contains(mongoException.getCode());
    }
    return false;
  }

  private static String errorSummary(final RuntimeException e) {
    if (e instanceof MongoBulkWriteException) {
      MongoBulkWriteException bulkWriteException = (MongoBulkWriteException) e;
      return "write error code(s) "
          + bulkWriteException.getWriteErrors().stream()
              .map(BulkWriteError::getCode)
              .distinct()
              .collect(Collectors.toList())
          + (bulkWriteException.getWriteConcernError() != null
              ? ", write concern error code " + bulkWriteException.getWriteConcernError().getCode()
              : "");
    }
    if (e instanceof MongoException) {
      return e.getClass().getSimpleName() + " code " + ((MongoException) e).getCode();
    }
    return e.getClass().getSimpleName();
  }

  private void checkTimeseries(final MongoNamespace namespace, final MongoSinkTopicConfig config) {
    if (!checkedTimeseriesNamespaces.contains(namespace)) {
      if (config.isTimeseries()) {
        validateCollection(mongoClient, namespace, config);
      }
      checkedTimeseriesNamespaces.add(namespace);
    }
  }

  private static void checkRateLimit(final MongoSinkTopicConfig config) {
    RateLimitSettings rls = config.getRateLimitSettings();
    if (rls.isTriggered()) {
      LOGGER.debug(
          "Rate limit settings triggering {}ms defer timeout after processing {} further batches for topic {}",
          rls.getTimeoutMs(),
          rls.getEveryN(),
          config.getTopic());
      try {
        Thread.sleep(rls.getTimeoutMs());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DataException("Rate limiting was interrupted", e);
      }
    }
  }

  private void handleTolerableWriteException(
      final List<SinkRecord> batch,
      final boolean ordered,
      final RuntimeException e,
      final boolean logErrors,
      final boolean tolerateErrors) {
    if (e instanceof MongoBulkWriteException) {
      MongoBulkWriteException bulkWriteException = (MongoBulkWriteException) e;
      LOGGER.trace("Failed to write some records into the sink", bulkWriteException);
      AnalyzedBatchFailedWithBulkWriteException analyzedBatch =
          new AnalyzedBatchFailedWithBulkWriteException(
              batch,
              ordered,
              bulkWriteException,
              errorReporter,
              StartedMongoSinkTask::log);
      if (logErrors) {
        LOGGER.error(
            "Failed to write some records into the sink: {} write error(s) with code(s) {}, "
                + "{} write concern error(s). Exception: {}",
            bulkWriteException.getWriteErrors().size(),
            bulkWriteException.getWriteErrors().stream()
                .map(writeError -> Integer.toString(writeError.getCode()))
                .distinct()
                .collect(Collectors.toList()),
            bulkWriteException.getWriteConcernError() != null ? 1 : 0,
            e.getClass().getName());
        LOGGER.debug(
            "Failed to put into the sink some records, see log entries below for the details", e);
        // Per-record breakdown: routes through the sanitized log() method below, so each entry is
        // logged at ERROR without record content (full per-record detail stays at DEBUG).
        analyzedBatch.log();
      }
      if (tolerateErrors) {
        analyzedBatch.report();
      } else {
        throw new DataException(
            String.format(
                "Failed to write some records into the sink: %d write error(s) with code(s) %s, "
                    + "%d write concern error(s)",
                bulkWriteException.getWriteErrors().size(),
                bulkWriteException.getWriteErrors().stream()
                    .map(writeError -> Integer.toString(writeError.getCode()))
                    .distinct()
                    .collect(Collectors.toList()),
                bulkWriteException.getWriteConcernError() != null ? 1 : 0));
      }
    } else {
      LOGGER.trace("Failed to write records into the sink", e);
      if (logErrors) {
        log(batch, e);
      }
      if (tolerateErrors) {
        DataException sanitized =
            new DataException(
                "Failed to write records into the sink. Exception type: " + e.getClass().getName());
        batch.forEach(record -> errorReporter.report(record, sanitized));
      } else {
        throw new DataException(
            "Failed to write records into the sink. Exception type: " + e.getClass().getName());
      }
    }
  }

  private static void log(final Collection<SinkRecord> records, final RuntimeException e) {
    LOGGER.error(
        "Failed to put {} records into the sink. Exception: {}",
        records.size(),
        e.getClass().getName());
    LOGGER.debug("Failed to put {} records into the sink (full detail)", records.size(), e);
  }

  /** Identifies a record across redeliveries. */
  private static final class RecordPosition {
    private final String topic;
    private final Integer partition;
    private final long offset;

    RecordPosition(final SinkRecord record) {
      topic = record.topic();
      partition = record.kafkaPartition();
      offset = record.kafkaOffset();
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof RecordPosition)) {
        return false;
      }
      RecordPosition other = (RecordPosition) o;
      return offset == other.offset
          && topic.equals(other.topic)
          && Objects.equals(partition, other.partition);
    }

    @Override
    public int hashCode() {
      return Objects.hash(topic, partition, offset);
    }
  }
}
