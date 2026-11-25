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

import static com.mongodb.kafka.connect.sink.MongoSinkConfig.CONNECTION_URI_CONFIG;
import static com.mongodb.kafka.connect.sink.MongoSinkConfig.SOCKET_READ_TIMEOUT_MS_CONFIG;
import static com.mongodb.kafka.connect.sink.SinkTestHelper.TEST_TOPIC;
import static org.apache.kafka.connect.runtime.SinkConnectorConfig.TOPICS_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mongodb.connection.SocketSettings;

import com.mongodb.kafka.connect.Versions;

final class MongoSinkTaskTest {

  @Test
  void readTimeoutDefaultsWhenTheConnectionStringHasNone() {
    assertEquals(
        MongoSinkConfig.SOCKET_READ_TIMEOUT_MS_DEFAULT, readTimeoutMs("mongodb://localhost"));
  }

  @Test
  void readTimeoutComesFromTheConfigWhenTheConnectionStringHasNone() {
    assertEquals(180000, readTimeoutMs("mongodb://localhost", 180000));
    assertEquals(30000, readTimeoutMs("mongodb://localhost", 30000));
  }

  @Test
  void readTimeoutKeepsAConnectionStringValueBelowTheConfiguredOne() {
    assertEquals(60000, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=60000", 180000));
  }

  @Test
  void aLargerConfiguredReadTimeoutRaisesTheMaximum() {
    assertEquals(150000, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=150000", 180000));
    assertEquals(180000, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=0", 180000));
    assertEquals(180000, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=200000", 180000));
  }

  @Test
  void aSmallerConfiguredReadTimeoutKeepsTheMaximum() {
    assertEquals(
        MongoSinkTask.MAX_READ_TIMEOUT_MS,
        readTimeoutMs("mongodb://localhost/?socketTimeoutMS=0", 30000));
    assertEquals(
        MongoSinkTask.MAX_READ_TIMEOUT_MS,
        readTimeoutMs("mongodb://localhost/?socketTimeoutMS=" + MongoSinkTask.MAX_READ_TIMEOUT_MS, 30000));
  }

  @Test
  void readTimeoutKeepsTheConnectionStringValue() {
    assertEquals(1234, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=1234"));
  }

  @Test
  void readTimeoutKeepsTheConnectionStringValueUpToTheMaximum() {
    assertEquals(
        MongoSinkTask.MAX_READ_TIMEOUT_MS,
        readTimeoutMs("mongodb://localhost/?socketTimeoutMS=" + MongoSinkTask.MAX_READ_TIMEOUT_MS));
  }

  @Test
  void readTimeoutReplacesZeroWithTheMaximum() {
    assertEquals(
        MongoSinkTask.MAX_READ_TIMEOUT_MS, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=0"));
  }

  @Test
  void readTimeoutReplacesAValueAboveTheMaximumWithTheMaximum() {
    assertEquals(
        MongoSinkTask.MAX_READ_TIMEOUT_MS,
        readTimeoutMs(
            "mongodb://localhost/?socketTimeoutMS=" + (MongoSinkTask.MAX_READ_TIMEOUT_MS + 1)));
  }

  @Test
  void applicationNameIncludesTheConnectorName() {
    Map<String, String> props = new HashMap<>();
    props.put("name", "lcc-abc123");
    assertEquals(
        "confluent-mongodb-sink/" + Versions.VERSION + "/lcc-abc123",
        MongoSinkTask.applicationName(props));
  }

  @Test
  void applicationNameDropsAConnectorNameThatIsTooLong() {
    Map<String, String> props = new HashMap<>();
    props.put("name", new String(new char[200]).replace('\0', 'x'));
    assertEquals("confluent-mongodb-sink/" + Versions.VERSION, MongoSinkTask.applicationName(props));
  }

  private static int readTimeoutMs(final String uri) {
    return readTimeoutMs(uri, null);
  }

  private static int readTimeoutMs(final String uri, final Integer configuredReadTimeoutMs) {
    Map<String, String> props = new HashMap<>();
    props.put(TOPICS_CONFIG, TEST_TOPIC);
    props.put(CONNECTION_URI_CONFIG, uri);
    props.put(MongoSinkTopicConfig.DATABASE_CONFIG, "db");
    if (configuredReadTimeoutMs != null) {
      props.put(SOCKET_READ_TIMEOUT_MS_CONFIG, String.valueOf(configuredReadTimeoutMs));
    }
    MongoSinkConfig cfg = new MongoSinkConfig(props);
    SocketSettings.Builder socket =
        SocketSettings.builder().applyConnectionString(cfg.getConnectionString());
    MongoSinkTask.setReadTimeout(socket, cfg);
    return socket.build().getReadTimeout(TimeUnit.MILLISECONDS);
  }
}
