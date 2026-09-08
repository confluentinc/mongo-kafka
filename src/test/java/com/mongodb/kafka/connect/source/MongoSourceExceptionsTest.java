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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;

class MongoSourceExceptionsTest {

  private static final String CANARY = "SENSITIVE_CANARY_memberCode_9f3a1c";

  @Test
  @DisplayName("keeps code/codeName/server for triage but drops the raw server error message")
  void mongoCommandExceptionIsSummarizedWithoutRawMessage() {
    BsonDocument response =
        new BsonDocument("ok", new BsonInt32(0))
            .append("code", new BsonInt32(286))
            .append("codeName", new BsonString("ChangeStreamHistoryLost"))
            .append("errmsg", new BsonString(CANARY));
    MongoCommandException e =
        new MongoCommandException(response, new ServerAddress("localhost", 27017));

    String detail = MongoSourceExceptions.safeErrorDetail(e);

    assertFalse(detail.contains(CANARY), () -> "raw server message leaked: " + detail);
    assertTrue(detail.contains("code: 286"), detail);
    assertTrue(detail.contains("codeName: ChangeStreamHistoryLost"), detail);
    assertTrue(detail.contains("localhost:27017"), detail);
    assertTrue(detail.contains("com.mongodb.MongoCommandException"), detail);
  }

  @Test
  @DisplayName("a non-Mongo throwable is reduced to its type, never its message")
  void nonMongoThrowableKeepsOnlyType() {
    String detail = MongoSourceExceptions.safeErrorDetail(new RuntimeException(CANARY));

    assertFalse(detail.contains(CANARY), () -> "raw message leaked: " + detail);
    assertEquals("Exception type: java.lang.RuntimeException", detail);
  }
}
