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

import static com.mongodb.kafka.connect.source.MongoSourceConfig.CONNECTION_URI_CONFIG;
import static com.mongodb.kafka.connect.source.MongoSourceConfig.POLL_AWAIT_TIME_MS_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mongodb.connection.SocketSettings;

final class MongoSourceTaskTest {

  @Test
  void readTimeoutUsesTheFloorForTheDefaultPollAwaitTime() {
    assertEquals(60_000, readTimeoutMs(new HashMap<>()));
  }

  @Test
  void readTimeoutStaysAbovePollAwaitTime() {
    Map<String, String> props = new HashMap<>();
    props.put(POLL_AWAIT_TIME_MS_CONFIG, "60000");
    assertEquals(90_000, readTimeoutMs(props));
  }

  @Test
  void readTimeoutKeepsTheConnectionStringValue() {
    Map<String, String> props = new HashMap<>();
    props.put(CONNECTION_URI_CONFIG, "mongodb://localhost/?socketTimeoutMS=1234");
    assertEquals(1234, readTimeoutMs(props));
  }

  private static int readTimeoutMs(final Map<String, String> props) {
    MongoSourceConfig cfg = new MongoSourceConfig(props);
    SocketSettings.Builder socket =
        SocketSettings.builder().applyConnectionString(cfg.getConnectionString());
    MongoSourceTask.setReadTimeout(socket, cfg);
    return socket.build().getReadTimeout(TimeUnit.MILLISECONDS);
  }
}
