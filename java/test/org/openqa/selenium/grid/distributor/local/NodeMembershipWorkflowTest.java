// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.distributor.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.grid.temporal.NodeMembershipSpec;
import org.openqa.selenium.grid.temporal.NodeMembershipWorkflow;

/**
 * Runs the real Workflow on Temporal's in-memory test server, with fake Activities in place of the
 * Distributor. The test sends the heartbeat Signals. Time skipping makes the timers fire at once.
 */
@Tag("UnitTests")
class NodeMembershipWorkflowTest {

  private static final String NODE_ID = "5d9a3c1e-0000-4000-8000-000000000001";
  private static final Duration HEARTBEAT = Duration.ofSeconds(60);

  private final List<String> calls = new CopyOnWriteArrayList<>();
  private TestWorkflowEnvironment env;
  private FakeActivities activities;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    activities = new FakeActivities(calls);
    Worker distributor = env.newWorker(NodeMembershipWorkflow.TASK_QUEUE);
    distributor.registerWorkflowImplementationTypes(NodeMembershipWorkflowImpl.class);
    distributor.registerActivitiesImplementations(activities);
    env.start();
  }

  private static NodeMembershipSpec spec(String nodeId, long registerPeriodSeconds) {
    NodeMembershipSpec spec = new NodeMembershipSpec();
    spec.nodeId = nodeId;
    spec.nodeUri = "http://node:5555";
    spec.heartbeatPeriodSeconds = HEARTBEAT.getSeconds();
    spec.registerPeriodSeconds = registerPeriodSeconds;
    return spec;
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void registersTheNodeWithOneCall() {
    assertThat(register(spec(NODE_ID, 120))).isEmpty();
    assertThat(calls.get(0)).isEqualTo("add");
  }

  @Test
  void keepsTryingWhileTheNodeStarts() {
    activities.addFailuresLeft.set(2);

    assertThat(register(spec(NODE_ID, 120))).isEmpty();
    result();
    assertThat(Collections.frequency(calls, "add")).isEqualTo(3);
  }

  @Test
  void tellsTheNodeWhyItWasRejected() {
    // Today the event bus drops the Node's event and the Node never learns why.
    activities.rejection = "The Node's registration secret is different from the Distributor's";

    assertThat(register(spec(NODE_ID, 120)))
        .isEqualTo("Rejected by the Distributor: " + activities.rejection);
    result();
    assertThat(calls).containsExactly("add");
  }

  @Test
  void saysWhenItGaveUpAfterRetrying() {
    activities.addFailuresLeft.set(Integer.MAX_VALUE);
    assertThat(register(spec(NODE_ID, 3)))
        .isEqualTo("No success after 3 s of retries: The Node is DOWN, not UP");
    assertThat(Collections.frequency(calls, "add")).isGreaterThan(1);
  }

  @Test
  void heartbeatsKeepTheNodeAndRefreshIt() {
    register(spec(NODE_ID, 120));

    for (int i = 0; i < 3; i++) {
      env.sleep(HEARTBEAT);
      heartbeat();
    }
    env.sleep(HEARTBEAT);

    assertThat(calls).doesNotContain("down");
    // Each heartbeat adds the Node again if the Distributor lost it, for example after a restart.
    assertThat(Collections.frequency(calls, "add")).isGreaterThan(1);
  }

  @Test
  void marksTheNodeDownThenRemovesItWhenHeartbeatsStop() {
    register(spec(NODE_ID, 120));
    result();

    assertThat(calls).containsExactly("add", "down", "remove");
  }

  @Test
  void aNodeThatComesBackIsMarkedUp() {
    register(spec(NODE_ID, 120));
    env.sleep(HEARTBEAT.multipliedBy(3));
    // Wait for the DOWN step to finish, so the heartbeat counts as a return.
    while (!calls.contains("down")) {
      env.sleep(Duration.ofSeconds(1));
    }

    heartbeat();
    result();

    assertThat(calls).containsExactly("add", "down", "up", "add", "down", "remove");
  }

  @Test
  void anUnhealthyNodeIsRemoved() {
    register(spec(NODE_ID, 120));
    heartbeat();

    env.getWorkflowClient()
        .newWorkflowStub(NodeMembershipWorkflow.class, NodeMembershipSpec.workflowId(NODE_ID))
        .unhealthy();
    result();

    assertThat(calls).endsWith("remove").doesNotContain("down");
  }

  /**
   * Starts the Workflow and waits for the registration answer, the way the Node does.
   *
   * @return an empty string when the Node was added, otherwise why it was not
   */
  private String register(NodeMembershipSpec spec) {
    NodeMembershipWorkflow workflow =
        env.getWorkflowClient()
            .newWorkflowStub(
                NodeMembershipWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(NodeMembershipWorkflow.TASK_QUEUE)
                    .setWorkflowId(NodeMembershipSpec.workflowId(spec.nodeId))
                    .setWorkflowIdConflictPolicy(
                        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                    .build());
    return WorkflowClient.executeUpdateWithStart(
        workflow::waitForRegistration,
        UpdateOptions.<String>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
        new WithStartWorkflowOperation<>(workflow::run, spec));
  }

  /** Waits for the current run of the Workflow to finish. */
  private void result() {
    env.getWorkflowClient()
        .newUntypedWorkflowStub(NodeMembershipSpec.workflowId(NODE_ID))
        .getResult(Void.class);
  }

  /** Sends the heartbeat Signal, the way the Node does. */
  private void heartbeat() {
    env.getWorkflowClient()
        .newWorkflowStub(NodeMembershipWorkflow.class, NodeMembershipSpec.workflowId(NODE_ID))
        .heartbeat();
  }

  static class FakeActivities implements NodeMembershipActivities {
    final List<String> calls;
    final AtomicInteger addFailuresLeft = new AtomicInteger();
    String rejection;

    FakeActivities(List<String> calls) {
      this.calls = calls;
    }

    @Override
    public void addNode(NodeMembershipSpec spec) {
      calls.add("add");
      if (rejection != null) {
        throw ApplicationFailure.newNonRetryableFailure(rejection, "Rejected");
      }
      if (addFailuresLeft.getAndDecrement() > 0) {
        throw ApplicationFailure.newFailure("The Node is DOWN, not UP", "NodeNotUp");
      }
    }

    @Override
    public void markNodeDown(NodeMembershipSpec spec) {
      calls.add("down");
    }

    @Override
    public void markNodeUp(NodeMembershipSpec spec) {
      calls.add("up");
    }

    @Override
    public void removeNode(NodeMembershipSpec spec) {
      calls.add("remove");
    }
  }
}
