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
package com.mongodb.kafka.connect.util;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;

final class MongoClientHelperTest {

  private static long readTimeoutMs(final String uri, final int defaultMs) {
    MongoClientSettings settings =
        MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(uri))
            .applyToSocketSettings(
                b ->
                    MongoClientHelper.applyReadTimeoutIfUnset(
                        b, new ConnectionString(uri), defaultMs))
            .build();
    return settings.getSocketSettings().getReadTimeout(MILLISECONDS);
  }

  @Test
  void appliesTheReadTimeoutWhenTheUriHasNone() {
    assertEquals(20000, readTimeoutMs("mongodb://localhost", 20000));
  }

  @Test
  void keepsTheReadTimeoutFromTheUri() {
    assertEquals(1234, readTimeoutMs("mongodb://localhost/?socketTimeoutMS=1234", 20000));
  }
}
