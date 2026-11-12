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

import static java.util.concurrent.TimeUnit.SECONDS;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;
import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.openqa.selenium.concurrent.GuardedRunnable;
import org.openqa.selenium.grid.temporal.NodeMembershipSpec;
import org.openqa.selenium.grid.temporal.NodeMembershipWorkflow;
import org.openqa.selenium.internal.Require;

/**
 * The Node's side of {@link NodeMembershipWorkflow}: register, then send a heartbeat Signal every
 * period.
 */
class NodeMembershipClient implements Closeable {

  private static final Logger LOG = Logger.getLogger(NodeMembershipClient.class.getName());

  private static final long EXTRA_WAIT_SECONDS = 10;

  private final WorkflowClient client;
  private final NodeMembershipSpec spec;
  private final ExecutorService registration =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "Node registration call"));
  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Node heartbeat"));
  private volatile boolean closed;

  NodeMembershipClient(WorkflowClient client, NodeMembershipSpec spec) {
    this.client = Require.nonNull("Workflow client", client);
    this.spec = Require.nonNull("Node membership spec", spec);
  }

  /**
   * Starts the membership Workflow and waits for the Distributor's answer. The wait is a little
   * longer than the register period, so a Workflow that gives up at the end of it can still say
   * why.
   *
   * @return empty when the Distributor added the Node, otherwise why it did not
   */
  Optional<String> register() {
    String workflowId = NodeMembershipSpec.workflowId(spec.nodeId);
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(NodeMembershipWorkflow.TASK_QUEUE)
            .setWorkflowId(workflowId)
            // A second call while the Workflow runs waits for the same answer.
            .setWorkflowIdConflictPolicy(
                WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
            .build();
    NodeMembershipWorkflow workflow = client.newWorkflowStub(NodeMembershipWorkflow.class, options);

    // The call waits for a Distributor Worker to pick up the Workflow, so it needs a time limit.
    Future<String> answer =
        registration.submit(() ->
            WorkflowClient.executeUpdateWithStart(
                workflow::waitForRegistration,
                UpdateOptions.<String>newBuilder()
                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                    .build(),
                new WithStartWorkflowOperation<>(workflow::run, spec)));
    String reason;
    try {
      reason = answer.get(spec.registerPeriodSeconds + EXTRA_WAIT_SECONDS, SECONDS);
      if (reason.isEmpty()) {
        return Optional.empty();
      }
    } catch (TimeoutException e) {
      answer.cancel(true);
      reason =
          String.format(
              "No answer from a Distributor within %d s. Is one running and connected to the"
                  + " same Temporal Service?",
              spec.registerPeriodSeconds + EXTRA_WAIT_SECONDS);
    } catch (ExecutionException e) {
      // Temporal could not run the call at all, for example because it is unreachable.
      reason = String.valueOf(e.getCause().getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reason = "Interrupted while waiting for the Distributor";
    }
    return Optional.of(
        String.format("%s (history: temporal workflow show --workflow-id %s)", reason, workflowId));
  }

  /**
   * Sends a heartbeat Signal every period, so the Workflow knows the Node is alive. Call it once
   * the Node is registered.
   */
  void startHeartbeats() {
    heartbeats.scheduleAtFixedRate(
        GuardedRunnable.guard(this::heartbeat),
        spec.heartbeatPeriodSeconds,
        spec.heartbeatPeriodSeconds,
        SECONDS);
  }

  /** Tells the Workflow the Node is alive. Registers again if the Distributor removed the Node. */
  void heartbeat() {
    try {
      client
          .newWorkflowStub(NodeMembershipWorkflow.class, NodeMembershipSpec.workflowId(spec.nodeId))
          .heartbeat();
    } catch (WorkflowNotFoundException e) {
      LOG.info("The Distributor removed this Node. Registering again.");
      Optional<String> rejection = register();
      if (rejection.isPresent() && !closed) {
        // Same as a first registration that fails: log it once and stop trying.
        LOG.severe("Node registration failed. Heartbeats stop. " + rejection.get());
        heartbeats.shutdown();
      }
    }
  }

  /** True once {@link #close()} ran: a failed registration then is not a real failure. */
  boolean isClosed() {
    return closed;
  }

  /**
   * Stops the heartbeats and any registration call, so the Distributor removes this Node. The
   * Temporal connection stays open: the process shares it.
   */
  @Override
  public void close() {
    closed = true;
    heartbeats.shutdownNow();
    registration.shutdownNow();
  }
}
