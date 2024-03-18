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

import org.bson.BsonDocument;

import com.mongodb.client.MongoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class MongoClientHelper {

  static final Logger LOGGER = LoggerFactory.getLogger(MongoClientHelper.class);
  private MongoClientHelper() {}

  public static boolean isAtleastFiveDotZero(final MongoClient mongoClient) {
    try {
      int maxWireVersion = mongoClient
              .getDatabase("admin")
              .runCommand(BsonDocument.parse("{hello: 1}"))
              .get("maxWireVersion", 0);

      LOGGER.info("Max wire version: {}", maxWireVersion);
      return maxWireVersion>= 13;
    } catch (RuntimeException e) {
      LOGGER.error("Exception occurred while checking MongoDB version", e);
      return false;
    }
  }
}
