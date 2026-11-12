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

import io.temporal.activity.ActivityInterface;

/** Changes to the Distributor's Grid model. They run on the Distributor. */
@ActivityInterface
public interface NodeMembershipActivities {

  /**
   * Checks that the Distributor can reach the Node and that both use the same registration secret,
   * then adds the Node. Does nothing when the Node is already there, so the Workflow calls it on
   * every heartbeat: that brings the Node back after a Distributor restart.
   */
  void addNode(NodeMembershipSpec spec);

  void markNodeDown(NodeMembershipSpec spec);

  void removeNode(NodeMembershipSpec spec);
}
