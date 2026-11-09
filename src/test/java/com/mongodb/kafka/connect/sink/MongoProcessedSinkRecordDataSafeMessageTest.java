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

import static com.mongodb.kafka.connect.sink.SinkTestHelper.TEST_TOPIC;
import static com.mongodb.kafka.connect.sink.SinkTestHelper.createSinkConfig;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.bson.BsonDocument;

/**
 * CC-47055: every failure surfaced by {@code MongoProcessedSinkRecordData.tryProcess} must (a) never
 * contain record content and (b) still contain the connector-owned text that error mappings match
 * on.
 *
 * <p>Each scenario plants {@link #CANARY} in the record key and/or value (and, where possible, in a
 * way that third-party code would echo it back in its exception message), runs it through the real
 * sink processing path and inspects the propagated exception.
 */
class MongoProcessedSinkRecordDataSafeMessageTest {

  private static final String CANARY = "CANARY_c47055_SECRET";

  private static final String PROVIDED_IN_KEY =
      "com.mongodb.kafka.connect.sink.processor.id.strategy.ProvidedInKeyStrategy";
  private static final String PROVIDED_IN_VALUE =
      "com.mongodb.kafka.connect.sink.processor.id.strategy.ProvidedInValueStrategy";
  private static final String FIELD_PATH_MAPPER =
      "com.mongodb.kafka.connect.sink.namespace.mapping.FieldPathNamespaceMapper";
  private static final String CHANGE_STREAM_HANDLER =
      "com.mongodb.kafka.connect.sink.cdc.mongodb.ChangeStreamHandler";
  private static final String DEBEZIUM_MONGO_HANDLER =
      "com.mongodb.kafka.connect.sink.cdc.debezium.mongodb.MongoDbHandler";

  private static final String DELETE_BY_KEY_CONFIG =
      format(
          "{'delete.on.null.values': 'true', 'document.id.strategy': '%s'}", PROVIDED_IN_KEY);
  private static final String PROVIDED_IN_VALUE_CONFIG =
      format("{'document.id.strategy': '%s'}", PROVIDED_IN_VALUE);
  private static final String FIELD_PATH_MAPPER_CONFIG =
      format(
          "{'namespace.mapper': '%s', 'namespace.mapper.value.collection.field': 'coll',"
              + " 'namespace.mapper.error.if.invalid': 'true'}",
          FIELD_PATH_MAPPER);

  static Stream<Arguments> scenarios() {
    return Stream.of(
        // --- Key delete path (WriteModelStrategyHelper.createKeyDeleteOneModel) ---
        Arguments.of(
            "delete: key is not JSON (bson JsonParseException echoes input)",
            record("not json " + CANARY, null),
            DELETE_BY_KEY_CONFIG,
            new String[] {
              "Could not create write model",
              "Could not convert key",
              "org.bson.json.JsonParseException"
            }),
        Arguments.of(
            "delete: key JSON without _id (mapping 473)",
            record(format("{foo: '%s'}", CANARY), null),
            DELETE_BY_KEY_CONFIG,
            new String[] {
              "Could not create write model",
              "Provided id strategy is used but the document structure either contained no _id"
            }),
        Arguments.of(
            "delete: key _id is null (mapping 868)",
            record(format("{_id: null, foo: '%s'}", CANARY), null),
            DELETE_BY_KEY_CONFIG,
            new String[] {"Could not create write model", "an _id of type BsonNull"}),
        Arguments.of(
            "delete: key is a JSON array, not a document",
            record(format("['%s']", CANARY), null),
            DELETE_BY_KEY_CONFIG,
            new String[] {"Could not create write model", "Could not convert key"}),

        // --- Value path (WriteModelStrategyHelper.createValueWriteModel + post processors) ---
        Arguments.of(
            "value: not JSON (bson JsonParseException echoes input)",
            record("{_id: 1}", "not json " + CANARY),
            "{}",
            new String[] {"Could not convert value", "org.bson.json.JsonParseException"}),
        Arguments.of(
            "value: ProvidedInValueStrategy without _id (mapping 473)",
            record("{_id: 1}", format("{foo: '%s'}", CANARY)),
            PROVIDED_IN_VALUE_CONFIG,
            new String[] {
              "Provided id strategy is used but the document structure either contained no _id"
            }),
        // --- Neither key nor value ---
        Arguments.of(
            "no key and no value (mapping 1137)",
            record(null, null),
            "{}",
            new String[] {"Invalid Sink Record neither key doc nor value doc were present"}),

        // --- Namespace mapping (createNamespace) ---
        Arguments.of(
            "namespace: field path missing",
            record("{_id: 1}", format("{other: '%s'}", CANARY)),
            FIELD_PATH_MAPPER_CONFIG,
            new String[] {"coll"}),
        Arguments.of(
            "namespace: field path has wrong type",
            record("{_id: 1}", format("{coll: {nested: '%s'}}", CANARY)),
            FIELD_PATH_MAPPER_CONFIG,
            new String[] {"Invalid type for DOCUMENT field path 'coll'"}),
        Arguments.of(
            "namespace: value not JSON",
            record("{_id: 1}", "not json " + CANARY),
            FIELD_PATH_MAPPER_CONFIG,
            new String[] {"Could not convert value", "org.bson.json.JsonParseException"}),

        // --- CDC handlers (buildWriteModelCDC) ---
        Arguments.of(
            "cdc change stream: operationType missing",
            record("{_id: 1}", format("{secret: '%s'}", CANARY)),
            format("{'change.data.capture.handler': '%s'}", CHANGE_STREAM_HANDLER),
            new String[] {"operationType"}),
        Arguments.of(
            "cdc change stream: value not JSON",
            record("{_id: 1}", "not json " + CANARY),
            format("{'change.data.capture.handler': '%s'}", CHANGE_STREAM_HANDLER),
            new String[] {"Could not convert value", "org.bson.json.JsonParseException"}),
        Arguments.of(
            "cdc debezium mongodb: unknown op",
            record("{id: 1}", format("{op: 'x', secret: '%s'}", CANARY)),
            format("{'change.data.capture.handler': '%s'}", DEBEZIUM_MONGO_HANDLER),
            new String[] {}),
        Arguments.of(
            "cdc debezium mongodb: value not JSON",
            record("{id: 1}", "not json " + CANARY),
            format("{'change.data.capture.handler': '%s'}", DEBEZIUM_MONGO_HANDLER),
            new String[] {"Could not convert value", "org.bson.json.JsonParseException"}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  @DisplayName("propagated exception never contains record content, keeps mapping text")
  void thrownExceptionIsSafe(
      final String name,
      final SinkRecord record,
      final String config,
      final String[] mustContain) {
    DataException thrown =
        assertThrows(
            DataException.class,
            () -> new MongoProcessedSinkRecordData(record, createSinkConfig(config)));
    assertSafe(thrown, mustContain);
  }

  @ParameterizedTest(name = "{0} (errors.tolerance=all)")
  @MethodSource("scenarios")
  @DisplayName("exception kept for DLQ / errant record reporter is equally safe")
  void toleratedExceptionIsSafe(
      final String name,
      final SinkRecord record,
      final String config,
      final String[] mustContain) {
    BsonDocument cfg = BsonDocument.parse(config);
    cfg.put("errors.tolerance", new org.bson.BsonString("all"));
    MongoProcessedSinkRecordData data =
        new MongoProcessedSinkRecordData(record, createSinkConfig(cfg.toJson()));
    assertNotNull(data.getException(), "expected the scenario to fail: " + name);
    assertSafe(data.getException(), mustContain);
  }

  // --- Direct tests of the chain walk, covering shapes the scenarios may not reach ---

  @Test
  @DisplayName("third-party root message is replaced by its class name")
  void thirdPartyRootIsDropped() {
    NumberFormatException thirdParty = thirdParty();
    String msg =
        MongoProcessedSinkRecordData.safeMessage(new DataException("connector text", thirdParty));
    assertEquals("connector text Caused by: java.lang.NumberFormatException", msg);
  }

  @Test
  @DisplayName("walk stops at the first third-party exception, even if connector ones follow")
  void walkStopsAtFirstThirdParty() {
    NumberFormatException thirdParty = thirdParty();
    thirdParty.initCause(new DataException("deeper connector text " + CANARY));
    String msg =
        MongoProcessedSinkRecordData.safeMessage(new DataException("outer", thirdParty));
    assertEquals("outer Caused by: java.lang.NumberFormatException", msg);
  }

  @Test
  @DisplayName("third-party top-level exception yields only its class name")
  void thirdPartyTopLevel() {
    assertEquals(
        "java.lang.NumberFormatException", MongoProcessedSinkRecordData.safeMessage(thirdParty()));
  }

  @Test
  @DisplayName("exceptions without a stack trace are treated as third-party")
  void noStackTraceIsNotTrusted() {
    RuntimeException noTrace = new RuntimeException(CANARY, null, false, false) {};
    String msg = MongoProcessedSinkRecordData.safeMessage(new DataException("outer", noTrace));
    assertFalse(msg.contains(CANARY), msg);
    assertTrue(msg.startsWith("outer Caused by: "), msg);
  }

  @Test
  @DisplayName("deep cause chains are bounded")
  void deepChainIsBounded() {
    Throwable chain = new DataException("level 0");
    for (int i = 1; i < 50; i++) {
      chain = new DataException("level " + i, chain);
    }
    String msg = MongoProcessedSinkRecordData.safeMessage(chain);
    assertEquals(10, msg.split(" Caused by: ").length);
  }

  private static NumberFormatException thirdParty() {
    try {
      Integer.parseInt(CANARY);
      throw new AssertionError("unreachable");
    } catch (NumberFormatException e) {
      assertTrue(e.getMessage().contains(CANARY), "precondition: JDK echoes the input");
      return e;
    }
  }

  private static void assertSafe(final Exception e, final String[] mustContain) {
    String message = e.getMessage();
    assertAll(
        () -> assertNull(e.getCause(), "sanitized exception must not carry the original chain"),
        () -> assertFalse(message.contains(CANARY), "record content leaked: " + message),
        () -> assertFalse(e.toString().contains(CANARY), "record content leaked: " + e),
        () -> {
          for (String expected : mustContain) {
            assertTrue(
                message.contains(expected),
                format("expected '%s' in message for mapping match: %s", expected, message));
          }
        });
  }

  private static SinkRecord record(final String key, final String value) {
    return new SinkRecord(
        TEST_TOPIC,
        1,
        key == null ? null : Schema.STRING_SCHEMA,
        key,
        value == null ? null : Schema.STRING_SCHEMA,
        value,
        20);
  }
}
