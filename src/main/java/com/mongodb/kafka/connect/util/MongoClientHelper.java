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

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.bson.BsonDocument;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.connection.SocketSettings;

public final class MongoClientHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(MongoClientHelper.class);
  private static final int MIN_REQUIRED_WIRE_VERSION = 13;

  private MongoClientHelper() {}

  /**
   * Sets the socket read timeout unless the connection string already specifies {@code
   * socketTimeoutMS}. Without a read timeout a hung connection blocks the calling thread forever.
   */
  public static void applyReadTimeoutIfUnset(
      final SocketSettings.Builder builder,
      final ConnectionString connectionString,
      final int readTimeoutMs) {
    if (connectionString.getSocketTimeout() == null) {
      builder.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS);
    }
  }

  public static boolean isAtleastFiveDotZero(final MongoClient mongoClient) {
    try {
      return mongoClient
              .getDatabase("admin")
              .runCommand(BsonDocument.parse("{hello: 1}"))
              .get("maxWireVersion", 0)
          >= MIN_REQUIRED_WIRE_VERSION;
    } catch (MongoCommandException | MongoSocketException | MongoTimeoutException e) {
      LOGGER.error("MongoDB operation failed", e);
      throw e;
    } catch (RuntimeException e) {
      LOGGER.error("Runtime exception occurred", e);
      throw e;
    }
  }
}
