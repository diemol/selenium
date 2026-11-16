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

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The membership of one Node in the Grid, from registration until the Distributor removes it. The
 * Workflow is the only owner of that membership: it adds the Node, marks it DOWN when its heartbeat
 * Signals stop, back UP when they return, and removes it. The Grid model changes run on the
 * Distributor.
 */
@WorkflowInterface
public interface NodeMembershipWorkflow {

  /** The Grid model changes run where the Grid model is: on the Distributor. */
  String TASK_QUEUE = "selenium-distributor";

  @WorkflowMethod
  void run(NodeMembershipSpec spec);

  /**
   * Blocks until the Distributor has added the Node or rejected it. The Node sends this together
   * with the Workflow start (Update-With-Start), so it gets the answer from a single call.
   *
   * @return an empty string when the Distributor added the Node, otherwise why it did not
   */
  @UpdateMethod
  String waitForRegistration();

  /** The Node is alive. Sent every heartbeat period. */
  @SignalMethod
  void heartbeat();

  /**
   * The Distributor's health checks failed too many times in a row. The Workflow removes the Node.
   */
  @SignalMethod
  void unhealthy();
}
