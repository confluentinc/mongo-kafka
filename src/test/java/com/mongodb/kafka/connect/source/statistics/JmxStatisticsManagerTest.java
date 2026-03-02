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
import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class JmxStatisticsManagerTest {

  private static final String SOURCE_TASK_QUERY =
      "com.mongodb.kafka.connect:type=source-task-metrics,*";
  private static final String DND_ATTRIBUTE = "connect.task.dnd";

  @Test
  @DisplayName("Should expose connect.task.dnd attribute on source-task-metrics MBeans and default to 0")
  void testDndMetricRegisteredWithDefault() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(true, "test-dnd-default");
    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      Set<ObjectName> results = mBeanServer.queryNames(new ObjectName(SOURCE_TASK_QUERY), null);
      ObjectName mbeanName = null;
      for (ObjectName name : results) {
        if (name.toString().contains("test-dnd-default")) {
          mbeanName = name;
          break;
        }
      }
      assertNotNull(mbeanName, "source-task-metrics MBean should be registered");

      Attribute attr = (Attribute) mBeanServer.getAttribute(mbeanName, DND_ATTRIBUTE);
      assertEquals(0L, attr.getValue(), "Default value should be 0 (revocable)");
    } finally {
      manager.close();
    }
  }

  @Test
  @DisplayName("Should set connect.task.dnd to 1 and back to 0")
  void testDndMetricTransitions() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(true, "test-dnd-transitions");
    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      Set<ObjectName> results = mBeanServer.queryNames(new ObjectName(SOURCE_TASK_QUERY), null);
      ObjectName copyBean = null;
      for (ObjectName name : results) {
        String n = name.toString();
        if (n.contains("test-dnd-transitions") && n.contains("copy-existing")) {
          copyBean = name;
          break;
        }
      }
      assertNotNull(copyBean, "copy-existing MBean should be registered");

      // Default: 0
      assertEquals(0L, ((Attribute) mBeanServer.getAttribute(copyBean, DND_ATTRIBUTE)).getValue());

      // Set exempt (simulates copy-existing start)
      manager.setDNDTask(true);
      assertEquals(1L, ((Attribute) mBeanServer.getAttribute(copyBean, DND_ATTRIBUTE)).getValue(),
          "Copy bean should be 1 (exempt) during copy-existing");

      // Clear exempt (simulates copy-existing finish)
      manager.setDNDTask(false);
      assertEquals(0L, ((Attribute) mBeanServer.getAttribute(copyBean, DND_ATTRIBUTE)).getValue(),
          "Copy bean should be 0 (revocable) after copy-existing completes");
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
  @DisplayName("Should keep connect.task.dnd at 0 when copy mode is not used")
  void testNoCopyModeKeepsDndAtZero() throws Exception {
    JmxStatisticsManager manager = new JmxStatisticsManager(false, "test-dnd-nocopy");
    try {
      MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
      Set<ObjectName> results = mBeanServer.queryNames(new ObjectName(SOURCE_TASK_QUERY), null);
      ObjectName mbeanName = null;
      for (ObjectName name : results) {
        if (name.toString().contains("test-dnd-nocopy")) {
          mbeanName = name;
          break;
        }
      }
      assertNotNull(mbeanName, "source-task-metrics MBean should be registered");

      // Without calling setDNDTask, metric should stay at 0
      Attribute attr = (Attribute) mBeanServer.getAttribute(mbeanName, DND_ATTRIBUTE);
      assertEquals(0L, attr.getValue(), "Should remain 0 when copy mode is not used");
    } finally {
      manager.close();
    }
  }
}
