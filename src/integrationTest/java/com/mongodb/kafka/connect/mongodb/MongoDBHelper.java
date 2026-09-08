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
package com.mongodb.kafka.connect.mongodb;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

public class MongoDBHelper
    implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {
  private static final String DEFAULT_URI = "mongodb://localhost:27017";
  private static final String URI_SYSTEM_PROPERTY_NAME = "org.mongodb.test.uri";
  private static final String DEFAULT_DATABASE_NAME = "MongoKafkaTest";
  private static final String MONGO_USERNAME = "test_username";
  private static final String MONGO_PASSWORD = "test_password";
  private static final String MONGO_IMAGE = "mongodb/mongodb-atlas-local";
  private static final String MONGO_TAG = "8.0";

  private static final MongoDBAtlasLocalContainer MONGO_CONTAINER =
      new MongoDBAtlasLocalContainer(MONGO_IMAGE + ":" + MONGO_TAG)
          .withEnv("MONGODB_INITDB_ROOT_USERNAME", MONGO_USERNAME)
          .withEnv("MONGODB_INITDB_ROOT_PASSWORD", MONGO_PASSWORD);

  private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBHelper.class);

  private ConnectionString connectionString;
  private MongoClient mongoClient;

  public MongoDBHelper() {}

  public MongoClient getMongoClient() {
    if (mongoClient == null) {
      mongoClient = MongoClients.create(getConnectionString());
    }
    return mongoClient;
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    startMongoContainer();
    getMongoClient();
  }

  @Override
  public void beforeEach(final ExtensionContext context) {
    if (mongoClient != null) {
      getDatabase().drop();
    }
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    if (mongoClient != null) {
      getDatabase().drop();
    }
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    if (mongoClient != null) {
      mongoClient.close();
      mongoClient = null;
    }
    connectionString = null;
    closeMongoDbContainer();
  }

  public String getDatabaseName() {
    String databaseName = getConnectionString().getDatabase();
    return databaseName != null ? databaseName : DEFAULT_DATABASE_NAME;
  }

  public MongoDatabase getDatabase() {
    String databaseName = getConnectionString().getDatabase();
    return getMongoClient()
        .getDatabase(databaseName != null ? databaseName : DEFAULT_DATABASE_NAME);
  }

  public ConnectionString getConnectionString() {
    if (connectionString == null) {
      String mongoURIProperty = System.getProperty(URI_SYSTEM_PROPERTY_NAME);
      String mongoURIString =
          mongoURIProperty == null || mongoURIProperty.isEmpty() ? DEFAULT_URI : mongoURIProperty;
      connectionString = new ConnectionString(mongoURIString);
      LOGGER.info("Connecting to: '{}'", connectionString);
    }
    return connectionString;
  }

  public static void startMongoContainer() {
    MONGO_CONTAINER.start();
    String mongoURI = MONGO_CONTAINER.getConnectionString()
        .replace("mongodb://", "mongodb://" + MONGO_USERNAME + ":" + MONGO_PASSWORD + "@");
    System.setProperty(URI_SYSTEM_PROPERTY_NAME, mongoURI);
  }

  public static void closeMongoDbContainer() {
    MONGO_CONTAINER.close();
  }
}
