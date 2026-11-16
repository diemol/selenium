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

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.openqa.selenium.grid.node.docker.DockerSessionStartResult.Outcome;
import org.slf4j.Logger;

/**
 * Workflow code must be deterministic: it only decides the order of the steps. Everything that
 * talks to Docker, the browser, the clock or the file system runs in {@link
 * DockerSessionActivities}.
 */
public class DockerSessionWorkflowImpl implements DockerSessionWorkflow {

  private static final Logger LOG = Workflow.getLogger(DockerSessionWorkflowImpl.class);

  /** Liveness checks are never more frequent than this, whatever the session timeout is. */
  static final Duration MIN_LIVENESS_INTERVAL = Duration.ofMinutes(1);

  private @Nullable DockerSessionStartResult startResult;
  private @Nullable Promise<Void> recorderRemoval;
  private boolean stopRequested;
  private String stage = "starting";

  @Override
  public String run(DockerSessionSpec spec) {
    Activities activities = new Activities(spec);
    String browser = spec.browserContainerName();

    // Cleanup for the creation steps. Compensations run in reverse order, and only when a step
    // fails before the session is ready.
    Saga saga = new Saga(new Saga.Options.Builder().setContinueWithError(true).build());
    Outcome failureOutcome = Outcome.RETRY;
    boolean recording = false;
    String sessionId;
    try {
      stage = "creating browser container";
      // Added before the call: if Docker creates the container but the reply is lost, the
      // compensation still removes it by name.
      saga.addCompensation(activities.cleanup::removeContainer, browser);
      activities.docker.createBrowserContainer(spec);

      stage = "starting browser container";
      String browserIp = activities.docker.startContainer(browser);
      String url = serverUrl(spec, browserIp);

      stage = "waiting for the browser server";
      activities.serverWait.waitForServer(url, spec.serverStartTimeoutSeconds);

      stage = "creating the WebDriver session";
      failureOutcome = Outcome.NOT_CREATED;
      DockerSessionStartResult session = activities.handshake.createSession(spec, url, browserIp);
      sessionId = session.sessionId;
      failureOutcome = Outcome.RETRY;

      if (spec.saveAssets) {
        activities.docker.saveCapabilities(spec, sessionId, session.capabilities);
      }
      if (spec.recordVideo) {
        recording = startRecorder(activities, spec, browserIp, sessionId);
      }
      startResult = session.created(url, recording);
    } catch (ActivityFailure e) {
      String message =
          String.format("Unable to %s (container %s): %s", stage, browser, rootMessage(e));
      LOG.warn(message);
      // Answer the Node first: the cleanup can take long while Docker is struggling.
      startResult = DockerSessionStartResult.failed(failureOutcome, message);
      stage = "removing containers after a failed start";
      saga.compensate();
      return finish("not created");
    }

    stage = "running";
    boolean orphaned = waitForStop(activities, spec, sessionId);

    stage = "stopping";
    tearDown(activities, spec, sessionId, recording);
    return finish(orphaned ? "removed after the Node lost the session" : "stopped");
  }

  @Override
  public DockerSessionStartResult waitForStart() {
    Workflow.await(() -> startResult != null);
    return startResult;
  }

  @Override
  public void stop() {
    stopRequested = true;
  }

  @Override
  public String getStage() {
    return stage;
  }

  /** Returns true when the session ended because the Node no longer had it. */
  private boolean waitForStop(Activities activities, DockerSessionSpec spec, String sessionId) {
    // The Node stops idle sessions itself. This loop only catches the case where the Node
    // restarted and forgot the session, so the Workflow removes the containers on its own.
    Duration checkEvery = Duration.ofSeconds(spec.sessionTimeoutSeconds);
    if (checkEvery.compareTo(MIN_LIVENESS_INTERVAL) < 0) {
      checkEvery = MIN_LIVENESS_INTERVAL;
    }
    while (!stopRequested) {
      boolean stopSignalled = Workflow.await(checkEvery, () -> stopRequested);
      if (!stopSignalled && !isSessionActive(activities, sessionId)) {
        LOG.warn("Session {} is unknown to its Node, removing its containers", sessionId);
        return true;
      }
    }
    return false;
  }

  /** A check that fails counts as active: not knowing is no reason to remove the session. */
  private boolean isSessionActive(Activities activities, String sessionId) {
    try {
      return activities.liveness.isSessionActive(sessionId);
    } catch (ActivityFailure e) {
      LOG.warn("Unable to check session {}, trying again later: {}", sessionId, rootMessage(e));
      return true;
    }
  }

  /** Same order as before: finish the video, keep the logs, then remove the browser. */
  private void tearDown(
      Activities activities, DockerSessionSpec spec, String sessionId, boolean recording) {
    long grace = spec.stopGracePeriodSeconds;
    if (spec.recordVideo && recording) {
      activities.cleanup.stopContainer(spec.recorderContainerName(), grace);
    }
    if (spec.saveAssets) {
      try {
        activities.logs.saveLogs(spec, sessionId);
      } catch (ActivityFailure e) {
        LOG.warn("Unable to save logs for session {}: {}", sessionId, rootMessage(e));
      }
    }
    activities.cleanup.stopContainer(spec.browserContainerName(), grace);
    activities.cleanup.removeContainer(spec.browserContainerName());
    if (spec.recordVideo) {
      removeRecorder(activities, spec.recorderContainerName());
    }
  }

  /** Waits for a removal started when the recorder failed, or removes the recorder now. */
  private void removeRecorder(Activities activities, String recorder) {
    if (recorderRemoval == null) {
      activities.cleanup.removeContainer(recorder);
    } else {
      recorderRemoval.get();
    }
  }

  /** A recorder that fails to start is removed, and the session goes on without video. */
  private boolean startRecorder(
      Activities activities, DockerSessionSpec spec, String browserIp, String sessionId) {
    String recorder = spec.recorderContainerName();
    try {
      activities.recorder.startRecorder(spec, browserIp, sessionId);
      return true;
    } catch (ActivityFailure e) {
      LOG.warn("Unable to start recorder {}, going on without video: {}", recorder, rootMessage(e));
      // Removed in the background, so the session starts without waiting. Teardown waits for it.
      recorderRemoval = Async.procedure(activities.cleanup::removeContainer, recorder);
      return false;
    }
  }

  /** Lets the update handler send its answer before the Workflow completes. */
  private String finish(String result) {
    Workflow.await(Workflow::isEveryHandlerFinished);
    stage = result;
    return result;
  }

  static String serverUrl(DockerSessionSpec spec, String containerIp) {
    String host = spec.browserHost != null ? spec.browserHost : containerIp;
    return String.format("http://%s:%d/wd/hub", host, spec.browserPort);
  }

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

  /** One stub per kind of step, because each kind needs different timeouts and retries. */
  private static final class Activities {
    final DockerSessionActivities docker;
    final DockerSessionActivities serverWait;
    final DockerSessionActivities handshake;
    final DockerSessionActivities recorder;
    final DockerSessionActivities logs;
    final DockerSessionActivities cleanup;
    final DockerSessionActivities liveness;

    Activities(DockerSessionSpec spec) {
      Duration serverStart = Duration.ofSeconds(spec.serverStartTimeoutSeconds);
      Duration grace = Duration.ofSeconds(spec.stopGracePeriodSeconds);

      // Short Docker API calls. A busy or restarting daemon gets a few more tries.
      docker =
          stub(
              Duration.ofMinutes(2),
              null,
              RetryOptions.newBuilder()
                  .setInitialInterval(Duration.ofSeconds(1))
                  .setMaximumInterval(Duration.ofSeconds(10))
                  .setMaximumAttempts(5)
                  .build());

      // The Activity polls on its own, so one attempt is enough. The heartbeat timeout notices a
      // dead worker long before the start timeout ends.
      serverWait =
          stub(
              serverStart.plusSeconds(30),
              Duration.ofSeconds(30),
              RetryOptions.newBuilder().setMaximumAttempts(1).build());

      // Not safe to repeat: each attempt starts a new browser session.
      handshake =
          stub(
              Duration.ofSeconds(spec.sessionTimeoutSeconds).plusSeconds(30),
              null,
              RetryOptions.newBuilder().setMaximumAttempts(1).build());

      // Video is optional, so one attempt is enough.
      recorder =
          stub(
              serverStart.plusMinutes(2),
              Duration.ofSeconds(30),
              RetryOptions.newBuilder().setMaximumAttempts(1).build());

      logs =
          stub(
              Duration.ofMinutes(2),
              null,
              RetryOptions.newBuilder()
                  .setInitialInterval(Duration.ofSeconds(1))
                  .setMaximumAttempts(3)
                  .build());

      // Cleanup must happen, so it retries with no time limit until Docker answers. If the Node
      // is down, the task waits in its queue until the Node starts again.
      cleanup =
          Workflow.newActivityStub(
              DockerSessionActivities.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(grace.plusMinutes(1))
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofSeconds(1))
                          .setMaximumInterval(Duration.ofMinutes(1))
                          .build())
                  .build());

      // The Node cannot answer while it starts, so the check keeps trying for a while.
      liveness =
          Workflow.newActivityStub(
              DockerSessionActivities.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(30))
                  .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofSeconds(1))
                          .setMaximumInterval(Duration.ofSeconds(10))
                          .build())
                  .build());
    }

    private static DockerSessionActivities stub(
        Duration startToClose, @Nullable Duration heartbeat, RetryOptions retry) {
      ActivityOptions.Builder options =
          ActivityOptions.newBuilder().setStartToCloseTimeout(startToClose).setRetryOptions(retry);
      if (heartbeat != null) {
        options.setHeartbeatTimeout(heartbeat);
      }
      return Workflow.newActivityStub(DockerSessionActivities.class, options.build());
    }
  }
}
