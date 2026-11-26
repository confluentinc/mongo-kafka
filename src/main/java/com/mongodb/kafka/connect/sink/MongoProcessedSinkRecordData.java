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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.bson.BsonDocument;

import com.mongodb.MongoNamespace;
import com.mongodb.client.model.WriteModel;

import com.mongodb.kafka.connect.sink.converter.SinkConverter;
import com.mongodb.kafka.connect.sink.converter.SinkDocument;
import com.mongodb.kafka.connect.sink.writemodel.strategy.WriteModelStrategyHelper;

final class MongoProcessedSinkRecordData {
  private static final Logger LOGGER = LoggerFactory.getLogger(MongoProcessedSinkRecordData.class);
  private static final SinkConverter SINK_CONVERTER = new SinkConverter();

  private final MongoSinkTopicConfig config;
  private final MongoNamespace namespace;
  private final SinkRecord sinkRecord;
  private final SinkDocument sinkDocument;
  private final WriteModel<BsonDocument> writeModel;
  private Exception exception;

  MongoProcessedSinkRecordData(final SinkRecord sinkRecord, final MongoSinkConfig sinkConfig) {
    this.sinkRecord = sinkRecord;
    this.config = sinkConfig.getMongoSinkTopicConfig(sinkRecord.topic());
    this.sinkDocument = SINK_CONVERTER.convert(sinkRecord);
    this.namespace = createNamespace();
    this.writeModel = createWriteModel();
  }

  public MongoSinkTopicConfig getConfig() {
    return config;
  }

  public MongoNamespace getNamespace() {
    return namespace;
  }

  public SinkRecord getSinkRecord() {
    return sinkRecord;
  }

  public WriteModel<BsonDocument> getWriteModel() {
    return writeModel;
  }

  public Exception getException() {
    return exception;
  }

  private MongoNamespace createNamespace() {
    return tryProcess(
            () -> Optional.of(config.getNamespaceMapper().getNamespace(sinkRecord, sinkDocument)))
        .orElse(null);
  }

  private WriteModel<BsonDocument> createWriteModel() {
    return config.getCdcHandler().isPresent() ? buildWriteModelCDC() : buildWriteModel();
  }

  private WriteModel<BsonDocument> buildWriteModel() {
    return tryProcess(
            () -> {
              config
                  .getPostProcessors()
                  .getPostProcessorList()
                  .forEach(pp -> pp.process(sinkDocument, sinkRecord));
              return Optional.ofNullable(
                  WriteModelStrategyHelper.createWriteModel(config, sinkDocument));
            })
        .orElse(null);
  }

  private WriteModel<BsonDocument> buildWriteModelCDC() {
    return tryProcess(
            () -> config.getCdcHandler().flatMap(cdcHandler -> cdcHandler.handle(sinkDocument)))
        .orElse(null);
  }

  private <T> Optional<T> tryProcess(final Supplier<Optional<T>> supplier) {
    try {
      return supplier.get();
    } catch (Exception e) {
      LOGGER.trace(
          "Unable to process record in topic:{} at partition:{}, offset:{}",
          sinkRecord.topic(),
          sinkRecord.kafkaPartition(),
          sinkRecord.kafkaOffset(),
          e);
      DataException sanitized = new DataException(safeMessage(e));
      exception = sanitized;
      if (config.logErrors()) {
        LOGGER.error(
            "Unable to process record in topic:{} at partition:{}, offset:{}. Cause: {}",
            sinkRecord.topic(),
            sinkRecord.kafkaPartition(),
            sinkRecord.kafkaOffset(),
            e.getClass().getName());
        LOGGER.debug(
            "Unable to process record in topic:{} at partition:{}, offset:{} (full detail)",
            sinkRecord.topic(),
            sinkRecord.kafkaPartition(),
            sinkRecord.kafkaOffset(),
            e);
      }
      if (!(config.tolerateErrors() || config.tolerateDataErrors())) {
        throw sanitized;
      }
    }
    return Optional.empty();
  }

  private static final String CONNECTOR_PACKAGE = "com.mongodb.kafka.connect.";
  private static final int MAX_CAUSE_DEPTH = 10;

  /**
   * Exact messages of driver/bson exceptions that are safe to surface: each is a fixed string, or
   * is built only from a fixed set of values, never from record content.
   * Matching is by exact equality, so no other third-party message can pass, whatever the driver
   * version. Never add a prefix, pattern or message that interpolates a value from the record.
   */
  private static final Set<String> SAFE_THIRD_PARTY_MESSAGES = buildSafeThirdPartyMessages();

  private static Set<String> buildSafeThirdPartyMessages() {
    Set<String> messages =
        new HashSet<>(
            Arrays.asList(
                // driver MongoNamespace, reached through the namespace mappers; mappings 13145,
                // 12339
                "state should be: collectionName is not empty",
                "state should be: databaseName is not empty",
                "collectionName can not be null",
                "databaseName can not be null",
                "fullName can not be null",
                // driver write models, built by the write-model strategies and CDC handlers
                "filter can not be null",
                "update can not be null",
                "replacement can not be null",
                "document can not be null",
                "options can not be null",
                // bson ByteBufferBsonInput, malformed BSON bytes; mapping 588
                "Found a BSON string that is not null-terminated"));
    // driver MongoNamespace.checkDatabaseNameValidity; mapping 1395. The quoted character is one
    // of this fixed set of prohibited characters.
    for (char c : new char[] {'\0', '/', '\\', ' ', '"', '.'}) {
      messages.add("state should be: databaseName does not contain '" + c + "'");
    }
    return Collections.unmodifiableSet(messages);
  }

  /**
   * Builds an exception message that is safe to surface to Connect framework logs, the DLQ and
   * error-mapping matching.
   *
   * <p>Walks the cause chain and keeps messages only from exceptions created by this connector's
   * own code, whose throw sites interpolate metadata and configuration (field names, BSON types,
   * topic/partition/offset) but not record content. The walk stops at the first exception created
   * elsewhere (org.bson, the MongoDB driver, Kafka, Jackson, ...), whose message may echo record
   * content, and records only its class name. If its message is one of {@link
   * #SAFE_THIRD_PARTY_MESSAGES}, the walk records "class: message" instead and continues, so error
   * mappings written against the original exception text still match.
   */
  static String safeMessage(final Throwable e) {
    StringBuilder sb = new StringBuilder();
    Throwable current = e;
    for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (depth > 0) {
        sb.append(" Caused by: ");
      }
      if (isConnectorOwned(current)) {
        sb.append(current.getMessage());
      } else if (SAFE_THIRD_PARTY_MESSAGES.contains(current.getMessage())) {
        sb.append(current.getClass().getName()).append(": ").append(current.getMessage());
      } else {
        sb.append(current.getClass().getName());
        break;
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return sb.toString();
  }

  private static boolean isConnectorOwned(final Throwable t) {
    StackTraceElement[] frames = t.getStackTrace();
    return frames.length > 0 && frames[0].getClassName().startsWith(CONNECTOR_PACKAGE);
  }
}
