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

import javax.management.Attribute;
import javax.management.AttributeNotFoundException;

/**
 * A separate MBean that exposes only the connect-task-dnd metric. Registered with an ObjectName
 * that includes task=&lt;numeric_id&gt; so that downstream consumers (e.g. ce-kafka's
 * parseTaskExemptResponse) can parse the task id as an integer.
 */
public class TaskStateDndMBean extends MongoMBean {

  private static final String DND_ATTRIBUTE = "connect-task-dnd";
  private final LatestMetric connectTaskDnd = registerLatest(DND_ATTRIBUTE);

  public TaskStateDndMBean(final String mBeanName) {
    super(mBeanName);
    connectTaskDnd.sample(0);
  }

  public void setDnd(final boolean exempt) {
    connectTaskDnd.sample(exempt ? 1 : 0);
  }

  @Override
  public Object getAttribute(final String attribute) throws AttributeNotFoundException {
    if (DND_ATTRIBUTE.equals(attribute)) {
      Object result = super.getAttribute(attribute);
      if (result instanceof Attribute) {
        return ((Attribute) result).getValue();
      }
      return result;
    }
    throw new AttributeNotFoundException("getAttribute failed: value not found for: " + attribute);
  }

  @Override
  protected String getDescription(final String name) {
    if (DND_ATTRIBUTE.equals(name)) {
      return "Whether the task should be exempt from rebalancing"
          + " (1 when snapshot/copy-existing is running, 0 otherwise).";
    }
    return null;
  }
}
