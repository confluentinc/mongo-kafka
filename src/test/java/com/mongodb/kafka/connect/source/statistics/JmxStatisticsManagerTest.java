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
package com.mongodb.kafka.connect.source.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Set;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class JmxStatisticsManagerTest {

  private static final String SOURCE_TASK_QUERY =
      "com.mongodb.kafka.connect:type=source-task-metrics,*";
  private static final String DND_ATTRIBUTE = "connect-task-dnd";

  private ObjectName findDndMBean(String connectorName) throws Exception {
    MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
    Set<ObjectName> results = mBeanServer.queryNames(new ObjectName(SOURCE_TASK_QUERY), null);
    for (ObjectName name : results) {
      String connector = name.getKeyProperty("connector");
      String task = name.getKeyProperty("task");
      if (connectorName.equals(connector) && task != null && !task.startsWith("source-task")) {
        return name;
      }
    }
    return null;
  }

  @Test
  @DisplayName("Should expose connect-task-dnd attribute on a separate task-context MBean and default to 0")
  void testDndMetricRegisteredWithDefault() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(true, "test-dnd-default");
    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      ObjectName dndBean = findDndMBean("test-dnd-default");
      assertNotNull(dndBean, "DND MBean should be registered");

      Long value = (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE);
      assertEquals(0L, value, "Default value should be 0 (revocable)");
    } finally {
      manager.close();
    }
  }

  @Test
  @DisplayName("Should set connect-task-dnd to 1 and back to 0")
  void testDndMetricTransitions() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(true, "test-dnd-transitions");
    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      ObjectName dndBean = findDndMBean("test-dnd-transitions");
      assertNotNull(dndBean, "DND MBean should be registered");

      // Default: 0
      assertEquals(0L, (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE));

      // Set exempt (simulates copy-existing start)
      manager.setDNDTask(true);
      assertEquals(1L, (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE),
          "DND bean should be 1 (exempt) during copy-existing");

      // Clear exempt (simulates copy-existing finish)
      manager.setDNDTask(false);
      assertEquals(0L, (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE),
          "DND bean should be 0 (revocable) after copy-existing completes");
    } finally {
      manager.close();
    }
  }

  @Test
  @DisplayName("Should unregister MBeans on close")
  void testCloseUnregistersMBeans() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(false, "test-dnd-close");
    MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

    // Verify registered
    Set<ObjectName> results = mBeanServer.queryNames(new ObjectName(SOURCE_TASK_QUERY), null);
    boolean found = results.stream().anyMatch(n -> n.toString().contains("test-dnd-close"));
    assertTrue(found, "MBeans should be registered before close");

    // Close and verify unregistered
    manager.close();
    results = mBeanServer.queryNames(new ObjectName(SOURCE_TASK_QUERY), null);
    found = results.stream().anyMatch(n -> n.toString().contains("test-dnd-close"));
    assertFalse(found, "MBeans should be unregistered after close");
  }

  @Test
  @DisplayName("DND MBean should have task as id without bean-type prefix")
  void testDndMBeanHasCleanTaskId() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(false, "test-dnd-taskid");
    try {
      ObjectName dndBean = findDndMBean("test-dnd-taskid");
      assertNotNull(dndBean, "DND MBean should be registered");
      String taskValue = dndBean.getKeyProperty("task");
      assertNotNull(taskValue, "task property should exist");
      // Should NOT contain bean type prefixes like "source-task-" or "source-task-copy-existing-"
      assertFalse(taskValue.startsWith("source-task"),
          "task property should not be prefixed with bean type, got: " + taskValue);
    } finally {
      manager.close();
    }
  }

  @Test
  @DisplayName("End-to-end: DND metric follows copy-existing lifecycle")
  void testDndMetricFollowsCopyExistingLifecycle() throws Exception {
    // Simulates the full copy-existing flow as done by MongoSourceTask and StartedMongoSourceTask:
    // 1. Task starts with copy-existing enabled -> setDNDTask(true)
    // 2. Copy-existing completes -> setDNDTask(false), switchToStreamStatistics()
    // 3. Task closes -> MBeans unregistered

    String connectorName = "test-dnd-e2e";
    MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

    // Step 1: Task starts with copy-existing (mirrors MongoSourceTask.start())
    JmxStatisticsManager manager = new JmxStatisticsManager(true, connectorName);
    try {
      ObjectName dndBean = findDndMBean(connectorName);
      assertNotNull(dndBean, "DND MBean should be registered on startup");

      // Before setDNDTask, default is 0
      assertEquals(0L, (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE),
          "DND should be 0 before copy-existing starts");

      // MongoSourceTask.start() calls setDNDTask(true) when shouldCopyData is true
      manager.setDNDTask(true);
      assertEquals(1L, (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE),
          "DND should be 1 while copy-existing is running");

      // Step 2: Copy-existing completes (mirrors StartedMongoSourceTask.getNextBatch())
      manager.setDNDTask(false);
      manager.switchToStreamStatistics();
      assertEquals(0L, (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE),
          "DND should be 0 after copy-existing completes and streaming begins");

      // Verify the DND MBean is still accessible after switching to stream mode
      assertNotNull(findDndMBean(connectorName),
          "DND MBean should remain registered after switching to stream statistics");
    } finally {
      // Step 3: Task closes
      manager.close();
    }

    // Verify DND MBean is unregistered after close
    ObjectName dndBeanAfterClose = findDndMBean(connectorName);
    assertEquals(null, dndBeanAfterClose,
        "DND MBean should be unregistered after close");
  }

  @Test
  @DisplayName("Should keep connect-task-dnd at 0 when copy mode is not used")
  void testNoCopyModeKeepsDndAtZero() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(false, "test-dnd-nocopy");
    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      ObjectName dndBean = findDndMBean("test-dnd-nocopy");
      assertNotNull(dndBean, "DND MBean should be registered");

      // Without calling setDNDTask, metric should stay at 0
      Long value = (Long) mBeanServer.getAttribute(dndBean, DND_ATTRIBUTE);
      assertEquals(0L, value, "Should remain 0 when copy mode is not used");
    } finally {
      manager.close();
    }
  }
}
