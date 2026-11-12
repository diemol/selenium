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

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Workflow code must be deterministic: it only decides when the Node joins, goes DOWN, and leaves.
 * Every change to the Grid model runs in {@link NodeMembershipActivities}.
 */
public class NodeMembershipWorkflowImpl implements NodeMembershipWorkflow {

  private static final Logger LOG = Workflow.getLogger(NodeMembershipWorkflowImpl.class);

  /** Missed heartbeats before the Node is marked DOWN, the same as the old purge. */
  static final int HEARTBEATS_BEFORE_DOWN = 2;

  /** Further missed heartbeats before a DOWN Node is removed. */
  static final int HEARTBEATS_BEFORE_REMOVAL = 4;

  private boolean registered;
  private @Nullable String rejection;
  private int heartbeats;

  @Override
  public void run(NodeMembershipSpec spec) {
    Duration period = Duration.ofSeconds(spec.heartbeatPeriodSeconds);
    NodeMembershipActivities join = joinActivities(spec);
    NodeMembershipActivities grid = gridActivities();

    if (spec.registered) {
      registered = true;
    } else {
      try {
        join.addNode(spec);
        registered = true;
      } catch (ActivityFailure e) {
        rejection = rootMessage(e);
        LOG.warn("Node {} at {} rejected: {}", spec.nodeId, spec.nodeUri, rejection);
        // Let the Update send the reason before the Workflow ends.
        Workflow.await(Workflow::isEveryHandlerFinished);
        return;
      }
    }

    while (!Workflow.getInfo().isContinueAsNewSuggested()) {
      if (awaitHeartbeat(period.multipliedBy(HEARTBEATS_BEFORE_DOWN))) {
        refresh(join, spec);
        continue;
      }
      grid.markNodeDown(spec);
      if (awaitHeartbeat(period.multipliedBy(HEARTBEATS_BEFORE_REMOVAL))) {
        refresh(join, spec);
        continue;
      }
      grid.removeNode(spec);
      return;
    }

    // A long-lived Node would make the history grow without end. Start a fresh one.
    Workflow.await(Workflow::isEveryHandlerFinished);
    spec.registered = true;
    Workflow.continueAsNew(spec);
  }

  @Override
  public void waitForRegistration() {
    Workflow.await(() -> registered || rejection != null);
    if (rejection != null) {
      throw ApplicationFailure.newNonRetryableFailure(rejection, "NodeRejected");
    }
  }

  @Override
  public void heartbeat() {
    heartbeats++;
  }

  /** Returns true when a heartbeat arrives before the timeout. */
  private boolean awaitHeartbeat(Duration timeout) {
    int seen = heartbeats;
    return Workflow.await(timeout, () -> heartbeats > seen);
  }

  /** Adds the Node again if the Distributor lost it, for example after a restart. */
  private static void refresh(NodeMembershipActivities join, NodeMembershipSpec spec) {
    try {
      join.addNode(spec);
    } catch (ActivityFailure e) {
      LOG.warn("Unable to refresh Node {}: {}", spec.nodeId, rootMessage(e));
    }
  }

  /**
   * Keeps trying for the register period, while the Node starts, like the old registration loop
   * did.
   */
  private static NodeMembershipActivities joinActivities(NodeMembershipSpec spec) {
    return Workflow.newActivityStub(
        NodeMembershipActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofSeconds(spec.registerPeriodSeconds))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setMaximumInterval(Duration.ofSeconds(10))
                    .build())
            .build());
  }

  /** In-memory changes on the Distributor. If it is down, they wait until it starts again. */
  private static NodeMembershipActivities gridActivities() {
    return Workflow.newActivityStub(
        NodeMembershipActivities.class,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
  }

  /** The message of the innermost cause: the one that says what went wrong. */
  static String rootMessage(Throwable failure) {
    String message = String.valueOf(failure.getMessage());
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      message =
          cause instanceof TemporalFailure
              ? ((TemporalFailure) cause).getOriginalMessage()
              : String.valueOf(cause.getMessage());
    }
    return message;
  }
}
