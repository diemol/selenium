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

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.grid.node.docker.DockerSessionStartResult.Outcome;

/**
 * Runs the real Workflow on Temporal's in-memory test server, with fake Activities in place of
 * Docker and the browser. Time skipping makes the session timers finish at once. Temporal stores
 * the spec as JSON, so every run also checks that it reads back.
 */
@Tag("UnitTests")
class DockerSessionWorkflowTest {

  private static final String TASK_QUEUE = "docker-session-test";
  private static final String BROWSER = "browser-chrome-1-abc";
  private static final String RECORDER = "recorder-chrome-1-abc";

  private TestWorkflowEnvironment env;
  private FakeActivities activities;
  private DockerSessionRunner runner;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    activities = new FakeActivities();
    Worker worker = env.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(DockerSessionWorkflowImpl.class);
    worker.registerActivitiesImplementations(activities);
    env.start();
    runner = new DockerSessionRunner(env.getWorkflowClient(), TASK_QUEUE);
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void createsTheSessionAndRemovesBothContainersOnStop() {
    DockerSessionStartResult startResult = runner.start(spec(true));

    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);
    assertThat(startResult.sessionId).isEqualTo("session-1");
    assertThat(startResult.url).isEqualTo("http://10.0.0.2:4444/wd/hub");
    assertThat(startResult.recording).isTrue();

    runner.stop("chrome-1-abc", Duration.ofMinutes(1));

    assertThat(activities.calls)
        .containsExactly(
            "create " + BROWSER,
            "start " + BROWSER,
            "wait http://10.0.0.2:4444/wd/hub",
            "session",
            "save capabilities",
            "recorder",
            "stop " + RECORDER,
            "save logs",
            "stop " + BROWSER,
            "remove " + BROWSER,
            "remove " + RECORDER);
    assertThat(result()).isEqualTo("stopped");
  }

  @Test
  void retriesDockerCallsThatFailForAWhile() {
    activities.startFailuresLeft.set(2);

    DockerSessionStartResult startResult = runner.start(spec(false));

    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);
    assertThat(Collections.frequency(activities.calls, "start " + BROWSER)).isEqualTo(3);
  }

  @Test
  void removesTheBrowserContainerWhenItCannotStart() {
    // Today this leaks a created container: nothing removes it when start() throws.
    activities.failOn = "start";

    DockerSessionStartResult startResult = runner.start(spec(false));

    assertThat(startResult.outcome).isEqualTo(Outcome.RETRY);
    // The Node gets its answer first, so wait for the Workflow before checking the cleanup.
    assertThat(result()).isEqualTo("not created");
    assertThat(activities.calls).endsWith("remove " + BROWSER);
  }

  @Test
  void removesTheBrowserContainerWhenTheServerNeverStarts() {
    activities.failOn = "wait";

    DockerSessionStartResult startResult = runner.start(spec(false));

    assertThat(startResult.outcome).isEqualTo(Outcome.RETRY);
    assertThat(startResult.message).contains("waiting for the browser server");
    assertThat(result()).isEqualTo("not created");
    assertThat(activities.calls).endsWith("remove " + BROWSER);
  }

  @Test
  void reportsNotCreatedWhenTheBrowserRefusesTheSession() {
    activities.failOn = "session";

    DockerSessionStartResult startResult = runner.start(spec(false));

    assertThat(startResult.outcome).isEqualTo(Outcome.NOT_CREATED);
    assertThat(result()).isEqualTo("not created");
    assertThat(activities.calls).endsWith("remove " + BROWSER);
    assertThat(Collections.frequency(activities.calls, "session")).isEqualTo(1);
  }

  @Test
  void goesOnWithoutVideoWhenTheRecorderFails() {
    // Today a recorder that fails in create or start leaks the browser container.
    activities.failOn = "recorder";

    DockerSessionStartResult startResult = runner.start(spec(true));

    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);
    assertThat(startResult.recording).isFalse();

    runner.stop("chrome-1-abc", Duration.ofMinutes(1));

    assertThat(result()).isEqualTo("stopped");
    assertThat(activities.calls).doesNotContain("stop " + RECORDER);
    // Removed once, in the background. Teardown waits for that removal instead of repeating it.
    assertThat(Collections.frequency(activities.calls, "remove " + RECORDER)).isEqualTo(1);
    assertThat(activities.calls).endsWith("stop " + BROWSER, "remove " + BROWSER);
  }

  @Test
  void aRecorderRemovalThatKeepsFailingDoesNotHoldTheSession() {
    // Docker refuses the removal a few times, as during an outage, then accepts it.
    activities.failOn = "recorder";
    activities.failRemovalOf = RECORDER;
    activities.removalFailuresLeft.set(3);

    DockerSessionStartResult startResult = runner.start(spec(true));

    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);
    assertThat(startResult.recording).isFalse();

    runner.stop("chrome-1-abc", Duration.ofMinutes(1));

    assertThat(result()).isEqualTo("stopped");
    assertThat(Collections.frequency(activities.calls, "remove " + RECORDER)).isEqualTo(4);
    // The removal retries in the background while teardown goes on. Teardown waits for it last.
    assertThat(activities.calls)
        .containsSubsequence("stop " + BROWSER, "remove " + BROWSER)
        .endsWith("remove " + RECORDER);
  }

  @Test
  void teardownRetriesUntilEveryContainerIsRemoved() {
    DockerSessionStartResult startResult = runner.start(spec(true));
    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);
    activities.failRemovalOf = BROWSER;
    activities.removalFailuresLeft.set(3);

    runner.stop("chrome-1-abc", Duration.ofMinutes(1));

    // The browser removal is retried in place, and the steps after it still run, in order.
    assertThat(result()).isEqualTo("stopped");
    assertThat(Collections.frequency(activities.calls, "remove " + BROWSER)).isEqualTo(4);
    assertThat(activities.calls).endsWith("remove " + BROWSER, "remove " + RECORDER);
  }

  @Test
  void aFailedStartIsReportedEvenWhenItsCleanupFails() {
    activities.failOn = "start";
    activities.failRemovalOf = BROWSER;
    activities.removalFailuresLeft.set(3);

    DockerSessionStartResult startResult = runner.start(spec(false));

    assertThat(startResult.outcome).isEqualTo(Outcome.RETRY);
    assertThat(result()).isEqualTo("not created");
    assertThat(activities.calls).endsWith("remove " + BROWSER);
  }

  @Test
  void removesTheContainersWhenTheNodeNoLongerHasTheSession() {
    DockerSessionStartResult startResult = runner.start(spec(false));
    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);

    // After a restart, the Node no longer owns the session, so the next liveness check says no.
    activities.sessionActive = false;
    env.sleep(Duration.ofMinutes(6));

    assertThat(result()).isEqualTo("removed after the Node lost the session");
    assertThat(activities.calls).endsWith("stop " + BROWSER, "remove " + BROWSER);
  }

  @Test
  void keepsTheContainersWhileTheLivenessCheckCannotAnswer() {
    DockerSessionStartResult startResult = runner.start(spec(false));
    assertThat(startResult.outcome).isEqualTo(Outcome.CREATED);

    // While the Node starts, the check fails. Not knowing is no reason to remove the containers.
    activities.livenessFails = true;
    env.sleep(Duration.ofMinutes(6));

    assertThat(activities.calls).contains("is active").doesNotContain("remove " + BROWSER);

    // The next check gets an answer and removes the containers of the lost session.
    activities.livenessFails = false;
    activities.sessionActive = false;
    env.sleep(Duration.ofMinutes(6));

    assertThat(result()).isEqualTo("removed after the Node lost the session");
  }

  @Test
  void keepsTheSessionWhileTheNodeStillHasIt() {
    DockerSessionStartResult startResult = runner.start(spec(false));

    env.sleep(Duration.ofMinutes(30));

    assertThat(activities.calls).doesNotContain("remove " + BROWSER);
    assertThat(Collections.frequency(activities.calls, "is active")).isGreaterThanOrEqualTo(5);
    runner.stop("chrome-1-abc", Duration.ofMinutes(1));
  }

  @Test
  void historyReplaysWithTheCurrentWorkflowCode() throws Exception {
    // Catches non-deterministic changes: replaying a recorded run must make the same decisions.
    DockerSessionStartResult startResult = runner.start(spec(true));
    runner.stop("chrome-1-abc", Duration.ofMinutes(1));

    WorkflowExecutionHistory history =
        env.getWorkflowClient().fetchHistory(DockerSessionSpec.workflowId("chrome-1-abc"));

    WorkflowReplayer.replayWorkflowExecution(history, DockerSessionWorkflowImpl.class);
  }

  private String result() {
    WorkflowClient client = env.getWorkflowClient();
    return WorkflowStub.fromTyped(
            client.newWorkflowStub(
                DockerSessionWorkflow.class, DockerSessionSpec.workflowId("chrome-1-abc")))
        .getResult(String.class);
  }

  private static DockerSessionSpec spec(boolean withRecorder) {
    DockerSessionSpec spec = new DockerSessionSpec();
    spec.sessionIdentifier = "chrome-1-abc";
    spec.desiredCapabilities = Map.of("browserName", "chrome");
    spec.browserImage = "selenium/standalone-chrome";
    spec.browserPort = 4444;
    spec.saveAssets = true;
    spec.serverStartTimeoutSeconds = 60;
    spec.sessionTimeoutSeconds = 300;
    spec.stopGracePeriodSeconds = 10;
    spec.recordVideo = withRecorder;
    return spec;
  }

  /** Records each call. {@link #failOn} makes one step fail without retries. */
  public static class FakeActivities implements DockerSessionActivities {
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger startFailuresLeft = new AtomicInteger();
    volatile String failOn = "";
    volatile boolean sessionActive = true;
    volatile boolean livenessFails;
    volatile String failRemovalOf = "";
    final AtomicInteger removalFailuresLeft = new AtomicInteger();

    private void step(String step, String call) {
      calls.add(call);
      if (failOn.equals(step)) {
        throw ApplicationFailure.newNonRetryableFailure(step + " failed", "TestFailure");
      }
    }

    @Override
    public String createBrowserContainer(DockerSessionSpec spec) {
      step("create", "create " + spec.browserContainerName());
      return "id-" + spec.browserContainerName();
    }

    @Override
    public String startContainer(String name) {
      step("start", "start " + name);
      if (startFailuresLeft.getAndDecrement() > 0) {
        throw new IllegalStateException("Docker daemon busy");
      }
      return "10.0.0.2";
    }

    @Override
    public void waitForServer(String url, long timeoutSeconds) {
      step("wait", "wait " + url);
    }

    @Override
    public DockerSessionStartResult createSession(
        DockerSessionSpec spec, String url, String containerIp) {
      step("session", "session");
      DockerSessionStartResult session = new DockerSessionStartResult();
      session.sessionId = "session-1";
      session.capabilities = new HashMap<>(spec.desiredCapabilities);
      session.dialect = "W3C";
      return session;
    }

    @Override
    public void saveCapabilities(
        DockerSessionSpec spec, String sessionId, Map<String, Object> capabilities) {
      step("capabilities", "save capabilities");
    }

    @Override
    public void startRecorder(DockerSessionSpec spec, String browserIp, String sessionId) {
      step("recorder", "recorder");
    }

    @Override
    public void saveLogs(DockerSessionSpec spec, String sessionId) {
      step("logs", "save logs");
    }

    @Override
    public void stopContainer(String name, long gracePeriodSeconds) {
      step("stop", "stop " + name);
    }

    @Override
    public void removeContainer(String name) {
      step("remove", "remove " + name);
      if (name.equals(failRemovalOf) && removalFailuresLeft.getAndDecrement() > 0) {
        throw new IllegalStateException("Docker is down");
      }
    }

    @Override
    public boolean isSessionActive(String sessionId) {
      calls.add("is active");
      if (livenessFails) {
        throw ApplicationFailure.newNonRetryableFailure("Node is still starting", "TestFailure");
      }
      return sessionActive;
    }
  }
}
