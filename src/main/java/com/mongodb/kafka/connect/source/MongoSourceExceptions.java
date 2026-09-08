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

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoServerException;

final class MongoSourceExceptions {

  private MongoSourceExceptions() {}

  /**
   * Builds a triage-friendly summary of a driver exception that is safe to log or place in an
   * exception surfaced to the Connect framework.
   *
   * <p>The raw {@link MongoException} message is the server's response for the failed command and
   * can echo record-derived content (e.g. the offending document fragment on a change-stream or
   * copy-existing failure). The framework logs any exception it is handed, and its cause chain,
   * to the shared connect log and the task-status trace, so neither the raw message nor the
   * exception instance must reach those surfaces. This summary keeps only the pieces that are safe
   * and useful for triage: the exception type, the server error code and codeName, and the server
   * address.
   */
  static String safeErrorDetail(final Throwable throwable) {
    StringBuilder detail =
        new StringBuilder("Exception type: ").append(throwable.getClass().getName());
    if (throwable instanceof MongoException) {
      MongoException e = (MongoException) throwable;
      detail.append(", code: ").append(e.getCode());
      if (e instanceof MongoCommandException) {
        detail.append(", codeName: ").append(((MongoCommandException) e).getErrorCodeName());
      }
      if (e instanceof MongoServerException) {
        detail.append(", server: ").append(((MongoServerException) e).getServerAddress());
      }
    }
    return detail.toString();
  }
}
