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
package com.mongodb.kafka.connect.sink.dlq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.bson.BsonDocument;

import com.mongodb.bulk.WriteConcernError;

/** {@link WriteConcernException#getMessage()} must never embed the server's raw error text. */
final class WriteConcernExceptionTest {

  private static final String SENSITIVE_MESSAGE =
      "waiting for replication timed out for document with _id: \"customer-42\"";

  @Test
  void messageExcludesServerErrorTextAndDetails() {
    WriteConcernError error =
        new WriteConcernError(
            64,
            "WriteConcernFailed",
            SENSITIVE_MESSAGE,
            BsonDocument.parse("{\"_id\": \"customer-42\"}"));

    WriteConcernException exception = new WriteConcernException(error);

    assertFalse(
        exception.getMessage().contains("customer-42"),
        "message must not contain the record's identifier value");
    assertFalse(
        exception.getMessage().contains("waiting for replication"),
        "message must not contain the raw MongoDB server error text");
  }

  @Test
  void messageStillCarriesCodeAndCodeNameForTriage() {
    WriteConcernError error =
        new WriteConcernError(64, "WriteConcernFailed", SENSITIVE_MESSAGE, BsonDocument.parse("{}"));

    WriteConcernException exception = new WriteConcernException(error);

    assertTrue(exception.getMessage().contains("code=64"));
    assertTrue(exception.getMessage().contains("codeName=WriteConcernFailed"));
  }

  @Test
  void messageFormatVersionWasBumped() {
    WriteConcernError error =
        new WriteConcernError(64, "WriteConcernFailed", SENSITIVE_MESSAGE, BsonDocument.parse("{}"));

    WriteConcernException exception = new WriteConcernException(error);

    assertEquals("v=2, code=64, codeName=WriteConcernFailed", exception.getMessage());
  }
}
