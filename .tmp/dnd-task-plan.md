# INIT-14217: DND Metric for MongoDB Source Connector

## Background

**Jira:** [INIT-14217](https://confluentinc.atlassian.net/browse/INIT-14217)

**Goal:** Prevent RAS (Resource Aware Scheduling) from restarting MongoDB Atlas Source connector tasks during snapshot/copy-existing operations by publishing a `connect.task.rebalance.exempt` DND metric.

**Problem:** In a fully managed setup, customers expect rollouts and restarts to have no visible downtime. However, connectors (especially single-task connectors like MongoDB Atlas) can become unavailable when RAS triggers a rebalance during a long-running copy-existing/snapshot operation. Honoring a DND metric allows RAS to skip restarts for tasks that are mid-snapshot.

**Metric spec:**

| Field       | Value                          |
| ----------- | ------------------------------ |
| Name        | `connect.task.rebalance.exempt` |
| Type        | Boolean (0 = revocable, 1 = exempt) |
| Tags        | `lcc_id`, `pcc_id`, `k8s_id`, `org_id` |
| Scope       | Task-level                     |

**Reference implementations:**

- [confluentinc/debezium#238](https://github.com/confluentinc/debezium/pull/238) — Introduces `TaskStateMetrics` MBean with `ConnectTaskRebalanceExempt` attribute, wired into the snapshot lifecycle
- [confluentinc/cc-docker-connect#5646](https://github.com/confluentinc/cc-docker-connect/pull/5646) — Adds JMX YAML mapping rules to expose the metric for debezium connectors

---

## Architecture Overview

```
MongoSourceTask.start()
  |
  +-- shouldCopyData=true ----> statisticsManager.setRebalanceExempt(true)
  |                                |
  |                                v
  |                      TaskStateMetrics MBean registered
  |                      ConnectTaskRebalanceExempt = 1
  |                                |
  |                      +---------+
  |                      v
  |           StartedMongoSourceTask.getNextBatch()
  |                |
  |                +-- copying in progress --> metric stays at 1
  |                |
  |                +-- copying finished (isCopying=false)
  |                       |
  |                       v
  |                statisticsManager.setRebalanceExempt(false)
  |                ConnectTaskRebalanceExempt = 0
  |
  +-- shouldCopyData=false ----> metric stays at default (0)
                                  task is always revocable
```

---

## Step-by-Step Plan

### Part 1: `mongo-kafka-private` repo changes

#### Step 1: Create `TaskStateMetrics` MBean class

**File:** `mongo-kafka/src/main/java/com/mongodb/kafka/connect/util/jmx/TaskStateMetrics.java` (new)

Create a new class extending `MongoMBean` that exposes a single DND attribute.

- Holds an `AtomicLong connectTaskRebalanceExempt` (default: 0)
- Uses the existing `LatestMetric` registration via `registerLatest("ConnectTaskRebalanceExempt")` to fit the `MongoMBean` / `DynamicMBean` infrastructure
- MBean ObjectName pattern: `com.mongodb.kafka.connect:type=task-state-metrics,connector={connectorName},task=source-task-{taskId}`

Key methods:

```java
public void setConnectTaskRebalanceExempt(boolean exempt)  // sets to 1 or 0
public long getConnectTaskRebalanceExempt()                 // returns current value
```

---

#### Step 2: Update `StatisticsManager` interface

**File:** `mongo-kafka/src/main/java/com/mongodb/kafka/connect/source/statistics/StatisticsManager.java`

Add a method to abstract DND metric control:

```java
void setRebalanceExempt(boolean exempt);
```

---

#### Step 3: Update `JmxStatisticsManager`

**File:** `mongo-kafka/src/main/java/com/mongodb/kafka/connect/source/statistics/JmxStatisticsManager.java`

- Add `TaskStateMetrics taskStateMetrics` field
- In constructor: instantiate and `register()` the `TaskStateMetrics` MBean using the name `com.mongodb.kafka.connect:type=task-state-metrics,connector={connectorName},task=source-task-{id}`
- Implement `setRebalanceExempt(boolean exempt)` by delegating to `taskStateMetrics.setConnectTaskRebalanceExempt(exempt)`
- In `close()`: call `taskStateMetrics.unregister()`

---

#### Step 4: Set metric to `exempt=true` when copy-existing begins

**File:** `mongo-kafka/src/main/java/com/mongodb/kafka/connect/source/MongoSourceTask.java`

After the `JmxStatisticsManager` is created and `shouldCopyData` is true, call:

```java
statisticsManager.setRebalanceExempt(true);
```

Location: around `MongoSourceTask.java:121`, after `statisticsManager = new JmxStatisticsManager(shouldCopyData, connectorName);`

---

#### Step 5: Set metric to `exempt=false` when copy-existing completes

**File:** `mongo-kafka/src/main/java/com/mongodb/kafka/connect/source/StartedMongoSourceTask.java`

In `getNextBatch()` at the point where `isCopying = false` is set after copy finishes, also call:

```java
statisticsManager.setRebalanceExempt(false);
```

Location: `StartedMongoSourceTask.java:599`, immediately after `isCopying = false;`

---

#### Step 6: Handle edge cases

| Scenario | Behavior |
| --- | --- |
| Task starts without copy mode (`shouldCopyData=false`) | Metric stays at default (0); RAS can freely rebalance |
| Task is interrupted during copy | MBean gets unregistered on `close()`; RAS treats task as revocable |
| Task finishes copy and transitions to change stream | Metric set to 0; RAS can rebalance normally |
| `StartedMongoSourceTask.close()` | No special action needed — `JmxStatisticsManager.close()` already unregisters all MBeans |

---

#### Step 7: Write unit tests

**New file:** `mongo-kafka/src/test/java/com/mongodb/kafka/connect/util/jmx/TaskStateMetricsTest.java`

- Test MBean registration and unregistration
- Test attribute value transitions (0 -> 1 -> 0)
- Test `getAttribute("ConnectTaskRebalanceExempt")` returns correct value

**Update existing tests:** `MongoSourceTaskTest.java` or `StartedMongoSourceTaskTest.java`

- Test that when `shouldCopyData=true`, the metric is set to 1 on start
- Test that when copying finishes, the metric is set back to 0
- Test that when `shouldCopyData=false`, the metric stays at 0

---

### Part 2: `cc-docker-connect` repo changes

#### Step 8: Add JMX YAML rules for the MongoDB connector DND metric

**File:** `cc-connect/include/opt/caas/config/jmx/mongodb-jmx.yaml` (create or update existing)

Add a rule similar to the debezium one from PR #5646:

```yaml
rules:
  # ConnectTaskRebalanceExempt - DND metric for MongoDB Source Connector
  - beans:
      - com.mongodb.kafka.connect:type=task-state-metrics,connector=*,task=*
    prefix: connect.task.
    metricAttribute:
      connector: param(connector)
      task: param(task)
    mapping:
      ConnectTaskRebalanceExempt:
        type: gauge
        metric: rebalance.exempt
        unit: "1"
        desc: >-
          Whether the task should be exempt from rebalancing
          (true when snapshot/copy-existing is running, false otherwise).
```

The exact bean pattern depends on the final MBean ObjectName chosen in Step 1.

---

## Key Differences from Debezium Implementation

| Aspect | Debezium (PR #238) | MongoDB Kafka Connector |
| --- | --- | --- |
| Base class | `Metrics` (custom JMX framework) | `MongoMBean` (`DynamicMBean`) |
| Snapshot trigger | `SnapshotMeter.snapshotStarted()` | `shouldCopyData` flag in `MongoSourceTask.start()` |
| Snapshot end trigger | `SnapshotMeter.snapshotCompleted()/Aborted()` | `isCopying = false` in `getNextBatch()` |
| MBean context | `context=task` | Separate `type=task-state-metrics` |
| Wiring | Through `ChangeEventSourceMetricsFactory` | Through `JmxStatisticsManager` |
| Files changed | 21 files | ~5-7 files |

---

## Files to Create/Modify Summary

| File | Action | Repo |
| --- | --- | --- |
| `util/jmx/TaskStateMetrics.java` | **Create** | `mongo-kafka-private` |
| `source/statistics/StatisticsManager.java` | Modify | `mongo-kafka-private` |
| `source/statistics/JmxStatisticsManager.java` | Modify | `mongo-kafka-private` |
| `source/MongoSourceTask.java` | Modify | `mongo-kafka-private` |
| `source/StartedMongoSourceTask.java` | Modify | `mongo-kafka-private` |
| `util/jmx/TaskStateMetricsTest.java` | **Create** | `mongo-kafka-private` |
| Existing source task tests | Modify | `mongo-kafka-private` |
| `mongodb-jmx.yaml` | **Create/Modify** | `cc-docker-connect` |
