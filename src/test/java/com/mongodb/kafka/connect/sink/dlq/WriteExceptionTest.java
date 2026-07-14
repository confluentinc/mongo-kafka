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

import com.mongodb.WriteError;

/** {@link WriteException#getMessage()} must never embed the server's raw write-error text. */
final class WriteExceptionTest {

  private static final String SENSITIVE_MESSAGE =
      "E11000 duplicate key error collection: UMV.MemberAdditionalDetails index: memberCode_1 "
          + "dup key: { memberCode: \"1234567890\" }";

  @Test
  void messageExcludesServerErrorTextAndDetails() {
    WriteError error =
        new WriteError(
            11000, SENSITIVE_MESSAGE, BsonDocument.parse("{\"memberCode\": \"1234567890\"}"));

    WriteException exception = new WriteException(error);

    assertFalse(
        exception.getMessage().contains("1234567890"),
        "message must not contain the record's business-key value");
    assertFalse(
        exception.getMessage().contains("memberCode"),
        "message must not contain the record's field name/value from the server error text");
    assertFalse(
        exception.getMessage().contains("dup key"),
        "message must not contain the raw MongoDB server error text");
  }

  @Test
  void messageStillCarriesTheErrorCodeForTriage() {
    WriteError error = new WriteError(11000, SENSITIVE_MESSAGE, BsonDocument.parse("{}"));

    WriteException exception = new WriteException(error);

    assertTrue(exception.getMessage().contains("code=11000"));
  }

  @Test
  void messageFormatVersionWasBumped() {
    WriteError error = new WriteError(11000, SENSITIVE_MESSAGE, BsonDocument.parse("{}"));

    WriteException exception = new WriteException(error);

    assertEquals("v=2, code=11000", exception.getMessage());
  }
}
