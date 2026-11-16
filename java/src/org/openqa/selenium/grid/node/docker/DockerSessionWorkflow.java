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

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The full life of one Docker session: create the browser container, wait for its server, start the
 * WebDriver session and the optional recorder, keep it while the session runs, and remove the
 * containers at the end. Every step that touches Docker or the browser is an Activity, so Temporal
 * retries it, records it, and finishes the cleanup even if this Node restarts in between.
 */
@WorkflowInterface
public interface DockerSessionWorkflow {

  /**
   * Runs the session lifecycle.
   *
   * @param spec everything needed to create the containers
   * @return how the session ended
   */
  @WorkflowMethod
  String run(DockerSessionSpec spec);

  /**
   * Blocks until the session is created or creation has failed. The Node sends this together with
   * the Workflow start (Update-With-Start), so it gets the answer from a single call.
   *
   * @return the new session, or why it could not be created
   */
  @UpdateMethod
  DockerSessionStartResult waitForStart();

  /** Asks the Workflow to stop the session and remove its containers. */
  @SignalMethod
  void stop();

  /**
   * Returns the current step, for debugging.
   *
   * @return a short description of the current step
   */
  @QueryMethod
  String getStage();
}
