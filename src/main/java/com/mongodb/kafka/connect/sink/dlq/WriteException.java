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

import com.mongodb.WriteError;

/**
 * The {@linkplain #getMessage() message} {@linkplain Formatter format} is {@code "v=2, code=%d"}.
 * {@link WriteError#getMessage()}/{@link WriteError#getDetails()} are omitted since they embed
 * record field values (e.g. {@code dup key: {...}}). We may change the format again in the
 * future, in which case the version (marked with {@code v}) will be incremented.
 */
public final class WriteException extends NoStackTraceDlqException {
  private static final long serialVersionUID = 1L;
  private static final int MESSAGE_FORMAT_VERSION = 2;

  public WriteException(final WriteError error) {
    super(
        String.format(
            Locale.ENGLISH,
            "v=%d, code=%d",
            MESSAGE_FORMAT_VERSION,
            error.getCode()));
  }
}
