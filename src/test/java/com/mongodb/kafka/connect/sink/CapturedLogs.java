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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

/** Collects what the sink task logs, from DEBUG up, until closed. */
final class CapturedLogs implements AutoCloseable {
  private final Logger logger = Logger.getLogger(MongoSinkTask.class);
  private final Level level = logger.getLevel();
  private final List<LoggingEvent> events = new ArrayList<>();
  private final AppenderSkeleton appender =
      new AppenderSkeleton() {
        @Override
        protected void append(final LoggingEvent event) {
          events.add(event);
        }

        @Override
        public void close() {}

        @Override
        public boolean requiresLayout() {
          return false;
        }
      };

  CapturedLogs() {
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
  }

  /** The messages logged at {@code level}. */
  List<String> at(final Level level) {
    return events.stream()
        .filter(event -> event.getLevel().equals(level))
        .map(LoggingEvent::getRenderedMessage)
        .collect(Collectors.toList());
  }

  @Override
  public void close() {
    logger.removeAppender(appender);
    logger.setLevel(level);
  }
}
