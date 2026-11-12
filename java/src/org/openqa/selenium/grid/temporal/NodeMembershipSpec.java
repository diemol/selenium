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

/**
 * Input of {@link NodeMembershipWorkflow}. Temporal stores it as JSON in the Workflow history, so
 * it only holds plain values.
 */
public class NodeMembershipSpec {

  public String nodeId = "";

  /** Where the Distributor reaches the Node. */
  public String nodeUri = "";

  public long heartbeatPeriodSeconds;

  /** How long the Distributor keeps trying to reach a new Node before it rejects it. */
  public long registerPeriodSeconds;

  /** True when the Workflow continues as new: the Node is already in the Distributor. */
  public boolean registered;

  public static String workflowId(String nodeId) {
    return "selenium-node-" + nodeId;
  }
}
