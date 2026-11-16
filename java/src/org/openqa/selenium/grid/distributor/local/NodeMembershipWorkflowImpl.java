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

package org.openqa.selenium.grid.distributor.local;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.openqa.selenium.grid.temporal.NodeMembershipSpec;
import org.openqa.selenium.grid.temporal.NodeMembershipWorkflow;
import org.slf4j.Logger;

/**
 * Workflow code must be deterministic: it only decides when the Node joins, goes DOWN, comes back,
 * and leaves. Every change to the Grid model runs in {@link NodeMembershipActivities}. The Node
 * proves it is alive with a heartbeat Signal every period.
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
  private boolean reportedUnhealthy;

  @Override
  public void run(NodeMembershipSpec spec) {
    NodeMembershipActivities join = joinActivities(spec);
    NodeMembershipActivities grid = gridActivities();

    if (spec.registered) {
      registered = true;
    } else {
      try {
        join.addNode(spec);
        registered = true;
      } catch (ActivityFailure e) {
        // A timeout means every retry failed. Anything else is an error that is not worth retrying.
        rejection =
            e.getRetryState() == RetryState.RETRY_STATE_TIMEOUT
                ? String.format(
                    "No success after %d s of retries: %s",
                    spec.registerPeriodSeconds, rootMessage(e))
                : "Rejected by the Distributor: " + rootMessage(e);
        LOG.warn("Node {} at {}: {}", spec.nodeId, spec.nodeUri, rejection, e);
        // Let the Update send the reason before the Workflow ends.
        Workflow.await(Workflow::isEveryHandlerFinished);
        return;
      }
    }

    Duration period = Duration.ofSeconds(spec.heartbeatPeriodSeconds);
    while (!Workflow.getInfo().isContinueAsNewSuggested()) {
      if (awaitHeartbeat(period.multipliedBy(HEARTBEATS_BEFORE_DOWN))) {
        refresh(join, spec);
        continue;
      }
      if (!reportedUnhealthy) {
        grid.markNodeDown(spec);
        if (awaitHeartbeat(period.multipliedBy(HEARTBEATS_BEFORE_REMOVAL))) {
          grid.markNodeUp(spec);
          refresh(join, spec);
          continue;
        }
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
  public String waitForRegistration() {
    Workflow.await(() -> registered || rejection != null);
    return rejection == null ? "" : rejection;
  }

  @Override
  public void heartbeat() {
    heartbeats++;
  }

  @Override
  public void unhealthy() {
    reportedUnhealthy = true;
  }

  /**
   * Returns true when a heartbeat arrives before the timeout. Returns false at once when the
   * Distributor reports the Node unhealthy, so the Workflow removes it.
   */
  private boolean awaitHeartbeat(Duration timeout) {
    int seen = heartbeats;
    Workflow.await(timeout, () -> heartbeats > seen || reportedUnhealthy);
    return heartbeats > seen && !reportedUnhealthy;
  }

  /**
   * Adds the Node again if the Distributor lost it, for example after a restart. Runs on every
   * heartbeat, as the event bus heartbeat did before.
   */
  private static void refresh(NodeMembershipActivities join, NodeMembershipSpec spec) {
    try {
      join.addNode(spec);
    } catch (ActivityFailure e) {
      LOG.warn("Unable to refresh Node {}: {}", spec.nodeId, rootMessage(e), e);
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
  private static String rootMessage(Throwable failure) {
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
