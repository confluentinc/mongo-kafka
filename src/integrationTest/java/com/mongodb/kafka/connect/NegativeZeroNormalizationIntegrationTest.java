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
package com.mongodb.kafka.connect;

import static com.mongodb.kafka.connect.source.MongoSourceConfig.COLLECTION_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.DATABASE_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.OUTPUT_FORMAT_VALUE_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.OUTPUT_SCHEMA_INFER_VALUE_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.PUBLISH_FULL_DOCUMENT_ONLY_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.STARTUP_MODE_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.TOPIC_PREFIX_CONFIG;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.kafka.connect.log.LogCapture;
import org.apache.log4j.Logger;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bson.BsonDocument;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import com.mongodb.kafka.connect.mongodb.MongoKafkaTestCase;
import com.mongodb.kafka.connect.source.MongoSourceConfig.OutputFormat;
import com.mongodb.kafka.connect.source.MongoSourceConfig.StartupConfig.StartupMode;

public class NegativeZeroNormalizationIntegrationTest extends MongoKafkaTestCase {

  private static final Logger TEST_LOGGER =
      Logger.getLogger(NegativeZeroNormalizationIntegrationTest.class);

  @BeforeEach
  void setUp() {
    assumeTrue(isReplicaSetOrSharded());
  }

  @AfterEach
  void tearDown() {
    getMongoClient()
        .listDatabaseNames()
        .into(new ArrayList<>())
        .forEach(
            i -> {
              if (i.startsWith(getDatabaseName())) {
                getMongoClient().getDatabase(i).drop();
              }
            });
  }

  private static final String DOC_TEMPLATE =
      "{\"_id\": %s, \"d\": {\"$numberDouble\": \"-0.0\"}, \"dec\": {\"$numberDecimal\": \"-0.0\"}}";

  // Expected template retained for clarity in assertions below, if needed in future updates.

  @Test
  @DisplayName("Source emits positive zero for negative-zero inputs")
  void testNegativeZeroNormalization() {
    MongoDatabase database = getDatabaseWithPostfix();
    MongoCollection<BsonDocument> source = database.getCollection("source", BsonDocument.class);

    try (LogCapture capture = new LogCapture(TEST_LOGGER)) {
      TEST_LOGGER.info("Starting NegativeZeroNormalizationIntegrationTest for namespace: "
          + source.getNamespace().getFullName());

      Properties sourceProperties = new Properties();
      sourceProperties.put(DATABASE_CONFIG, source.getNamespace().getDatabaseName());
      sourceProperties.put(COLLECTION_CONFIG, source.getNamespace().getCollectionName());
      sourceProperties.put(TOPIC_PREFIX_CONFIG, "copy");
      sourceProperties.put(STARTUP_MODE_CONFIG, StartupMode.COPY_EXISTING.propertyValue());
      sourceProperties.put(PUBLISH_FULL_DOCUMENT_ONLY_CONFIG, "true");
      sourceProperties.put(OUTPUT_FORMAT_VALUE_CONFIG, OutputFormat.JSON.name());
      sourceProperties.put(OUTPUT_SCHEMA_INFER_VALUE_CONFIG, "true");
      addSourceConnector(sourceProperties);

      String topicName =
          format(
              "copy.%s.%s",
              source.getNamespace().getDatabaseName(), source.getNamespace().getCollectionName());
      TEST_LOGGER.info("Source connector added. Topic: " + topicName);

      List<BsonDocument> originals =
          IntStream.range(1, 4)
              .mapToObj(i -> BsonDocument.parse(format(DOC_TEMPLATE, i)))
              .collect(toList());

      TEST_LOGGER.info("Prepared " + originals.size() + " original documents to insert");
      source.insertMany(originals);
      TEST_LOGGER.info("Inserted " + originals.size() + " documents into "
          + source.getNamespace().getFullName());

      // Read produced values from the source topic as UTF-8 JSON strings
      TEST_LOGGER.info("Consuming " + originals.size() + " records from topic " + topicName);
      List<String> produced =
          getProduced(
              topicName,
              new MappingDeserializer<>(b -> new String(b.get(), StandardCharsets.UTF_8)),
              new MappingDeserializer<>(b -> new String(b.get(), StandardCharsets.UTF_8)),
              c -> c.value(),
              originals.size(),
              10);
      TEST_LOGGER.info("Consumed " + produced.size() + " records from topic " + topicName);
      produced.forEach(v -> TEST_LOGGER.info("Produced value: " + v));

      // Assert normalization: no "-0.0" occurrences for the fields, and positive zero present
      TEST_LOGGER.info("Asserting negative-zero normalization on produced values");
      produced.forEach(
          v -> {
            assertFalse(
                v.contains("\"$numberDouble\": \"-0.0\"")
                    || v.contains("\"$numberDecimal\": \"-0.0\""),
                () -> "Found negative zero in value: " + v);
            assertTrue(
                v.contains("\"$numberDouble\": \"0.0\"")
                    && v.contains("\"$numberDecimal\": \"0.0\""),
                () -> "Did not find positive zero normalization in value: " + v);
          });
      TEST_LOGGER.info("Negative zero normalization assertions passed");
    }
  }
}


