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
package com.mongodb.kafka.connect.util.jmx.internal;

import java.util.concurrent.atomic.AtomicLong;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;

/**
 * A separate MBean that exposes only the connect-task-dnd metric. Registered with an ObjectName
 * that includes task=&lt;numeric_id&gt; so that downstream consumers (e.g. ce-kafka's
 * parseTaskExemptResponse) can parse the task id as an integer.
 */
public class TaskStateDndMBean implements DynamicMBean {

  private static final String DND_ATTRIBUTE = "connect-task-dnd";
  private final AtomicLong connectTaskDnd = new AtomicLong(0);
  private String mBeanName;

  public TaskStateDndMBean(final String mBeanName) {
    this.mBeanName = mBeanName;
  }

  public void setDnd(final boolean exempt) {
    connectTaskDnd.set(exempt ? 1 : 0);
  }

  public long getDnd() {
    return connectTaskDnd.get();
  }

  @Override
  public Object getAttribute(final String attribute) throws AttributeNotFoundException {
    if (DND_ATTRIBUTE.equals(attribute)) {
      return connectTaskDnd.get();
    }
    throw new AttributeNotFoundException(
        "getAttribute failed: value not found for: " + attribute);
  }

  @Override
  public AttributeList getAttributes(final String[] attributes) {
    AttributeList list = new AttributeList();
    for (String name : attributes) {
      if (DND_ATTRIBUTE.equals(name)) {
        list.add(new Attribute(name, connectTaskDnd.get()));
      }
    }
    return list;
  }

  @Override
  public MBeanInfo getMBeanInfo() {
    MBeanAttributeInfo[] attrs =
        new MBeanAttributeInfo[] {
          new MBeanAttributeInfo(
              DND_ATTRIBUTE,
              long.class.getName(),
              "Whether the task should be exempt from rebalancing"
                  + " (1 when snapshot/copy-existing is running, 0 otherwise).",
              true,
              false,
              false)
        };
    return new MBeanInfo(this.getClass().getName(), null, attrs, null, null, null);
  }

  @Override
  public Object invoke(final String actionName, final Object[] params, final String[] signature) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAttribute(final Attribute attribute) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AttributeList setAttributes(final AttributeList attributes) {
    throw new UnsupportedOperationException();
  }

  public void register() {
    mBeanName = MBeanServerUtils.registerMBean(this, mBeanName);
  }

  public void unregister() {
    MBeanServerUtils.unregisterMBean(mBeanName);
  }

  public String getName() {
    return mBeanName;
  }
}
