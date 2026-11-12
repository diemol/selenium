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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.openqa.selenium.internal.Require;

/** The Node's side of {@link NodeMembershipWorkflow}: register, then send heartbeats. */
public class NodeMembershipClient {

  private static final Logger LOG = Logger.getLogger(NodeMembershipClient.class.getName());

  private final WorkflowClient client;
  private final NodeMembershipSpec spec;

  public NodeMembershipClient(WorkflowClient client, NodeMembershipSpec spec) {
    this.client = Require.nonNull("Workflow client", client);
    this.spec = Require.nonNull("Node membership spec", spec);
  }

  /**
   * Starts the membership Workflow and waits up to the register period for the Distributor's
   * answer.
   *
   * @return empty when the Distributor added the Node, otherwise why it did not
   */
  public Optional<String> register() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(NodeMembershipWorkflow.TASK_QUEUE)
            .setWorkflowId(NodeMembershipSpec.workflowId(spec.nodeId))
            // A second call while the Workflow runs waits for the same answer.
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build();
    NodeMembershipWorkflow workflow = client.newWorkflowStub(NodeMembershipWorkflow.class, options);

    // The call waits for a Distributor Worker to pick up the Workflow, so it needs a time limit.
    CompletableFuture<Void> answer =
        CompletableFuture.runAsync(() ->
            WorkflowClient.executeUpdateWithStart(
                workflow::waitForRegistration,
                UpdateOptions.<Void>newBuilder()
                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                    .build(),
                new WithStartWorkflowOperation<>(workflow::run, spec)));
    try {
      answer.get(spec.registerPeriodSeconds * 1000, MILLISECONDS);
      return Optional.empty();
    } catch (TimeoutException e) {
      return Optional.of(
          String.format(
              "No answer from a Distributor within %s seconds. Is one running with"
                  + " --temporal-node-registration true?",
              spec.registerPeriodSeconds));
    } catch (ExecutionException e) {
      return Optional.of(NodeMembershipWorkflowImpl.rootMessage(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of("Interrupted while waiting for the Distributor");
    }
  }

  /** Tells the Workflow the Node is alive. Registers again if the Distributor removed the Node. */
  public void heartbeat() {
    try {
      client
          .newWorkflowStub(NodeMembershipWorkflow.class, NodeMembershipSpec.workflowId(spec.nodeId))
          .heartbeat();
    } catch (WorkflowNotFoundException e) {
      LOG.info("The Distributor removed this Node. Registering again.");
      register().ifPresent(reason -> LOG.severe("Registration failed: " + reason));
    }
  }
}
