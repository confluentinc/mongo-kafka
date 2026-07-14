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

import java.util.Formatter;
import java.util.Locale;

import com.mongodb.bulk.WriteConcernError;

/**
 * The {@linkplain #getMessage() message} {@linkplain Formatter format} is {@code "v=2, code=%d,
 * codeName=%s"}. {@link WriteConcernError#getMessage()}/{@link WriteConcernError#getDetails()}
 * are omitted since they embed record field values. We may change the format again in the
 * future, in which case the version (marked with {@code v}) will be incremented.
 */
public final class WriteConcernException extends NoStackTraceDlqException {
  private static final long serialVersionUID = 1L;
  private static final int MESSAGE_FORMAT_VERSION = 2;

  public WriteConcernException(final WriteConcernError error) {
    super(
        String.format(
            Locale.ENGLISH,
            "v=%d, code=%d, codeName=%s",
            MESSAGE_FORMAT_VERSION,
            error.getCode(),
            error.getCodeName()));
  }
}
