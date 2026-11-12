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

package org.openqa.selenium.grid.node.httpd;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.grid.temporal.NodeMembershipSpec;
import org.openqa.selenium.grid.temporal.NodeMembershipWorkflow;

/** Runs the Node's client against a fake membership Workflow that answers at once. */
@Tag("UnitTests")
class NodeMembershipClientTest {

  private static final String NODE_ID = "5d9a3c1e-0000-4000-8000-000000000003";

  private TestWorkflowEnvironment env;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(NodeMembershipWorkflow.TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(FakeMembership.class);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void returnsNothingWhenTheNodeIsAccepted() {
    FakeMembership.answer = "";

    assertThat(client().register()).isEmpty();
  }

  @Test
  void addsTheHistoryCommandToTheReason() {
    FakeMembership.answer = "Rejected by the Distributor: wrong secret";

    assertThat(client().register())
        .contains(
            "Rejected by the Distributor: wrong secret (history: temporal workflow show"
                + " --workflow-id selenium-node-"
                + NODE_ID
                + ")");
  }

  @Test
  void registersAgainWhenTheWorkflowIsGone() {
    FakeMembership.answer = "";
    NodeMembershipClient client = client();
    client.register();
    int runs = FakeMembership.runs.get();

    // The fake Workflow ends right after it answers, as after the Distributor removed the Node.
    client.heartbeat();

    assertThat(FakeMembership.runs.get()).isEqualTo(runs + 1);
  }

  private NodeMembershipClient client() {
    NodeMembershipSpec spec = new NodeMembershipSpec();
    spec.nodeId = NODE_ID;
    spec.nodeUri = "http://node:5555";
    spec.heartbeatPeriodSeconds = 60;
    spec.registerPeriodSeconds = 120;
    return new NodeMembershipClient(env.getWorkflowClient(), spec);
  }

  /** Answers the registration with a fixed value, then ends. Counts its runs. */
  public static class FakeMembership implements NodeMembershipWorkflow {
    static volatile String answer = "";
    static final AtomicInteger runs = new AtomicInteger();

    @Override
    public void run(NodeMembershipSpec spec) {
      runs.incrementAndGet();
    }

    @Override
    public void heartbeat() {}

    @Override
    public void unhealthy() {}

    @Override
    public String waitForRegistration() {
      return answer;
    }
  }
}
