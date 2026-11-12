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

package org.openqa.selenium.grid.temporal;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Runs the real Workflow on Temporal's in-memory test server, with fake Activities in place of the
 * Distributor. Time skipping makes the heartbeat timers finish at once.
 */
@Tag("UnitTests")
class NodeMembershipWorkflowTest {

  private static final String NODE_ID = "5d9a3c1e-0000-4000-8000-000000000001";
  private static final Duration HEARTBEAT = Duration.ofSeconds(60);

  private TestWorkflowEnvironment env;
  private FakeActivities activities;
  private NodeMembershipClient membership;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    activities = new FakeActivities();
    Worker worker = env.newWorker(NodeMembershipWorkflow.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(NodeMembershipWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    env.start();

    NodeMembershipSpec spec = new NodeMembershipSpec();
    spec.nodeId = NODE_ID;
    spec.nodeUri = "http://node:5555";
    spec.heartbeatPeriodSeconds = HEARTBEAT.getSeconds();
    spec.registerPeriodSeconds = 120;
    membership = new NodeMembershipClient(env.getWorkflowClient(), spec);
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void registersTheNodeWithOneCall() {
    assertThat(membership.register()).isEmpty();
    assertThat(activities.calls).containsExactly("add");
  }

  @Test
  void keepsTryingWhileTheNodeStarts() {
    activities.addFailuresLeft.set(2);

    assertThat(membership.register()).isEmpty();
    assertThat(Collections.frequency(activities.calls, "add")).isEqualTo(3);
  }

  @Test
  void tellsTheNodeWhyItWasRejected() {
    // Today the event bus drops the Node's event and the Node never learns why.
    activities.rejection = "The Node's registration secret is different from the Distributor's";

    assertThat(membership.register()).contains(activities.rejection);
    result();
    assertThat(activities.calls).containsExactly("add");
  }

  @Test
  void marksTheNodeDownThenRemovesItWhenHeartbeatsStop() {
    membership.register();

    result();

    assertThat(activities.calls).containsExactly("add", "down", "remove");
  }

  @Test
  void heartbeatsKeepTheNodeAndRefreshIt() {
    membership.register();

    for (int i = 0; i < 3; i++) {
      env.sleep(HEARTBEAT);
      membership.heartbeat();
    }
    env.sleep(HEARTBEAT);

    assertThat(activities.calls).doesNotContain("down");
    // Each heartbeat adds the Node again if the Distributor lost it, for example after a restart.
    assertThat(Collections.frequency(activities.calls, "add")).isGreaterThan(1);
  }

  @Test
  void aRemovedNodeRegistersAgainOnItsNextHeartbeat() {
    membership.register();
    result();

    membership.heartbeat();

    assertThat(activities.calls).containsExactly("add", "down", "remove", "add");
  }

  /** Waits for the current run of the Workflow to finish. */
  private void result() {
    env.getWorkflowClient()
        .newUntypedWorkflowStub(NodeMembershipSpec.workflowId(NODE_ID))
        .getResult(Void.class);
  }

  static class FakeActivities implements NodeMembershipActivities {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger addFailuresLeft = new AtomicInteger();
    String rejection;

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
    public void removeNode(NodeMembershipSpec spec) {
      calls.add("remove");
    }
  }
}
