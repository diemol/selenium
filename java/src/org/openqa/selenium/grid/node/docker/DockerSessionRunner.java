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

package org.openqa.selenium.grid.node.docker;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateStage;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openqa.selenium.internal.Require;

/** The Node's side of {@link DockerSessionWorkflow}: start a session and stop it. */
public class DockerSessionRunner {

  private static final Logger LOG = Logger.getLogger(DockerSessionRunner.class.getName());

  private final WorkflowClient client;
  private final String taskQueue;

  public DockerSessionRunner(WorkflowClient client, String taskQueue) {
    this.client = Require.nonNull("Workflow client", client);
    this.taskQueue = Require.nonNull("Task queue", taskQueue);
  }

  /**
   * Starts the session Workflow and waits until the session has started or failed to start.
   *
   * @param spec the session to create
   * @return the new session, or why it could not be created
   */
  public DockerSessionStartResult start(DockerSessionSpec spec) {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowId(DockerSessionSpec.workflowId(spec.sessionIdentifier))
            .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
            .build();
    DockerSessionWorkflow workflow = client.newWorkflowStub(DockerSessionWorkflow.class, options);

    return WorkflowClient.executeUpdateWithStart(
        workflow::waitForStart,
        UpdateOptions.<DockerSessionStartResult>newBuilder()
            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
            .build(),
        new WithStartWorkflowOperation<>(workflow::run, spec));
  }

  /**
   * Asks the Workflow to remove the session's containers and waits up to {@code wait} for it. If
   * the wait ends first, the Workflow still finishes the cleanup on its own.
   *
   * @param sessionIdentifier the identifier from the {@link DockerSessionSpec}
   * @param wait how long to wait for the cleanup
   */
  public void stop(String sessionIdentifier, Duration wait) {
    String workflowId = DockerSessionSpec.workflowId(sessionIdentifier);
    try {
      DockerSessionWorkflow workflow =
          client.newWorkflowStub(DockerSessionWorkflow.class, workflowId);
      workflow.stop();
      WorkflowStub.fromTyped(workflow).getResult(wait.toMillis(), MILLISECONDS, String.class);
    } catch (TimeoutException e) {
      LOG.warning(
          String.format(
              "Cleanup of %s takes longer than %s, it goes on in the background",
              workflowId, wait));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Unable to stop session workflow " + workflowId, e);
    }
  }
}
