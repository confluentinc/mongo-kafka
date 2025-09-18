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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import org.apache.kafka.connect.storage.StringConverter;
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
      "{_id: %s, d: {\\$numberDouble: \"-0.0\"}, dec: {\\$numberDecimal: \"-0.0\"}}";

  private static final String EXPECTED_TEMPLATE =
      "{_id: %s, d: {\\$numberDouble: \"0.0\"}, dec: {\\$numberDecimal: \"0.0\"}}";

  @Test
  @DisplayName("Negative zero is normalized to positive zero in round trip")
  void testNegativeZeroNormalization() {
    MongoDatabase database = getDatabaseWithPostfix();
    MongoCollection<BsonDocument> source = database.getCollection("source", BsonDocument.class);
    MongoCollection<BsonDocument> destination =
        database.getCollection("destination", BsonDocument.class);

    Properties sourceProperties = new Properties();
    sourceProperties.put(DATABASE_CONFIG, source.getNamespace().getDatabaseName());
    sourceProperties.put(COLLECTION_CONFIG, source.getNamespace().getCollectionName());
    sourceProperties.put(TOPIC_PREFIX_CONFIG, "copy");
    sourceProperties.put(STARTUP_MODE_CONFIG, StartupMode.COPY_EXISTING.propertyValue());
    sourceProperties.put(PUBLISH_FULL_DOCUMENT_ONLY_CONFIG, "true");
    sourceProperties.put(OUTPUT_FORMAT_VALUE_CONFIG, OutputFormat.JSON.name());
    sourceProperties.put(OUTPUT_SCHEMA_INFER_VALUE_CONFIG, "true");
    addSourceConnector(sourceProperties);

    Properties sinkProperties = createSinkProperties();
    sinkProperties.put(
        "topics",
        format(
            "copy.%s.%s",
            source.getNamespace().getDatabaseName(), source.getNamespace().getCollectionName()));
    sinkProperties.put(DATABASE_CONFIG, destination.getNamespace().getDatabaseName());
    sinkProperties.put(COLLECTION_CONFIG, destination.getNamespace().getCollectionName());
    sinkProperties.put("key.converter", StringConverter.class.getName());
    sinkProperties.put("value.converter", StringConverter.class.getName());
    addSinkConnector(sinkProperties);

    List<BsonDocument> originals =
        IntStream.range(1, 4)
            .mapToObj(i -> BsonDocument.parse(format(DOC_TEMPLATE, i)))
            .collect(toList());
    List<BsonDocument> expected =
        IntStream.range(1, 4)
            .mapToObj(i -> BsonDocument.parse(format(EXPECTED_TEMPLATE, i)))
            .collect(toList());

    source.insertMany(originals);

    assertCollection(expected, destination);
  }
}


