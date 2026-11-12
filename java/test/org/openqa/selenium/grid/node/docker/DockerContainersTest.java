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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.docker.ContainerConfig;
import org.openqa.selenium.docker.Image;
import org.openqa.selenium.docker.ImageId;
import org.openqa.selenium.docker.internal.ImageSummary;
import org.openqa.selenium.json.Json;

@Tag("UnitTests")
class DockerContainersTest {

  private static final Capabilities RECORDING_CAPS =
      new ImmutableCapabilities("browserName", "chrome", "se:recordVideo", true);
  private static final Image CHROME = image("selenium/standalone-chrome:latest");
  private static final Image VIDEO = image("selenium/video:latest");

  /** Stubs the inherited Node environment so the tests do not depend on the test runner's own. */
  private static class TestContainers extends DockerContainers {

    private final Map<String, String> inheritedEnvVars;

    TestContainers(
        @Nullable Image videoImage, boolean runningInDocker, Map<String, String> inheritedEnvVars) {
      super(
          List.of(),
          videoImage,
          new DockerAssetsPath("/opt/selenium/assets", "/opt/selenium/assets"),
          "grid-network",
          runningInDocker,
          Map.of(),
          List.of(),
          Map.of());
      this.inheritedEnvVars = inheritedEnvVars;
    }

    @Override
    Map<String, String> getBrowserContainerEnvVars(Capabilities sessionCapabilities) {
      return new HashMap<>(inheritedEnvVars);
    }
  }

  private static Image image(String name) {
    return new Image(new ImageSummary(new ImageId("sha256:" + name.hashCode()), List.of(name)));
  }

  private static DockerSessionSpec spec(Capabilities caps) {
    DockerSessionSpec spec = new DockerSessionSpec();
    spec.sessionIdentifier = "chrome-1-abc";
    spec.desiredCapabilities = caps.asMap();
    spec.browserPort = 50123;
    return spec;
  }

  /** Selenium's Json writes a ContainerConfig as the body of Docker's create request. */
  private static Map<String, Object> dockerRequest(ContainerConfig config) {
    Json json = new Json();
    return json.toType(json.toJson(config), Json.MAP_TYPE);
  }

  private static List<String> strings(Object list) {
    return ((List<?>) list).stream().map(String::valueOf).collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> hostConfig(ContainerConfig config) {
    return (Map<String, Object>) dockerRequest(config).get("HostConfig");
  }

  @Test
  void inlineRecordingAlwaysWritesToASessionSubfolder() {
    DockerContainers containers = new TestContainers(null, true, Map.of());

    Map<String, String> envVars = containers.createBrowserContainerEnvVars(RECORDING_CAPS);

    assertThat(envVars).containsEntry("SE_RECORD_VIDEO", "true");
    assertThat(envVars).containsEntry("SE_VIDEO_RECORD_STANDALONE", "true");
    assertThat(envVars).containsEntry("SE_VIDEO_SESSION_SUBFOLDER", "true");
    assertThat(envVars).containsEntry("SE_VIDEO_FILE_NAME", "auto");
  }

  @Test
  void inlineRecordingOverridesAnInheritedSubfolderOptOut() {
    // The browser container binds the assets root, so a flat layout would scatter every session's
    // video into it.
    DockerContainers containers =
        new TestContainers(null, true, Map.of("SE_VIDEO_SESSION_SUBFOLDER", "false"));

    Map<String, String> envVars = containers.createBrowserContainerEnvVars(RECORDING_CAPS);

    assertThat(envVars).containsEntry("SE_VIDEO_SESSION_SUBFOLDER", "true");
  }

  @Test
  void inlineRecordingOverridesAnInheritedFixedFileName() {
    // video.sh only creates the session subfolder on its dynamic naming path, so a fixed name
    // would silently disable the subfolder.
    DockerContainers containers =
        new TestContainers(null, true, Map.of("SE_VIDEO_FILE_NAME", "video.mp4"));

    Map<String, String> envVars = containers.createBrowserContainerEnvVars(RECORDING_CAPS);

    assertThat(envVars).containsEntry("SE_VIDEO_FILE_NAME", "auto");
    assertThat(envVars).containsEntry("SE_VIDEO_SESSION_SUBFOLDER", "true");
  }

  @Test
  void noVideoEnvVarsWhenSessionDoesNotRecord() {
    DockerContainers containers = new TestContainers(null, true, Map.of());

    Map<String, String> envVars =
        containers.createBrowserContainerEnvVars(
            new ImmutableCapabilities("browserName", "chrome"));

    assertThat(envVars).doesNotContainKey("SE_RECORD_VIDEO");
    assertThat(envVars).doesNotContainKey("SE_VIDEO_SESSION_SUBFOLDER");
    assertThat(envVars).doesNotContainKey("SE_VIDEO_FILE_NAME");
  }

  @Test
  void videoContainerDoesNotInheritTheSessionSubfolderSetting() {
    // The video container's bind mount is already per-session, so a "true" inherited from the Node
    // would nest twice. The value is pinned blank, which nothing inherited can survive, and which
    // leaves the image's own default in charge.
    DockerContainers containers = new TestContainers(VIDEO, true, Map.of());

    Map<String, String> envVars = containers.getVideoContainerEnvVars(RECORDING_CAPS, "10.0.0.5");

    assertThat(envVars).containsEntry("SE_VIDEO_SESSION_SUBFOLDER", "");
  }

  @Test
  void sessionAssetsGoToASessionFolder() {
    DockerContainers containers = new TestContainers(null, true, Map.of());

    assertThat(containers.savesAssets()).isTrue();
    assertThat(containers.sessionFolder("session-1").toString())
        .isEqualTo("/opt/selenium/assets/session-1");
  }

  @Test
  void onlyARecordingSessionWithAVideoImageUsesARecorder() {
    assertThat(new TestContainers(VIDEO, true, Map.of()).usesRecorder(RECORDING_CAPS)).isTrue();
    assertThat(new TestContainers(null, true, Map.of()).usesRecorder(RECORDING_CAPS)).isFalse();
    assertThat(
            new TestContainers(VIDEO, true, Map.of())
                .usesRecorder(new ImmutableCapabilities("browserName", "chrome")))
        .isFalse();
  }

  @Test
  void browserIsNamedAfterTheSessionAndMapsItsPortOutsideDocker() {
    ContainerConfig browser =
        new TestContainers(null, false, Map.of()).browser(spec(RECORDING_CAPS), CHROME);

    assertThat(browser.getName()).isEqualTo("browser-chrome-1-abc");
    assertThat(hostConfig(browser).get("PortBindings").toString())
        .contains("4444/tcp")
        .contains("50123");
  }

  @Test
  void inlineRecordingBindsTheAssetsFolderToTheBrowserContainer() {
    ContainerConfig browser =
        new TestContainers(null, true, Map.of()).browser(spec(RECORDING_CAPS), CHROME);

    assertThat(strings(hostConfig(browser).get("Binds"))).contains("/opt/selenium/assets:/videos");
  }

  @Test
  void recorderGetsTheBrowserIpAndASessionVideoFolder() {
    ContainerConfig recorder =
        new TestContainers(VIDEO, true, Map.of())
            .recorder(spec(RECORDING_CAPS), "10.0.0.2", "session-1", null);

    assertThat(recorder.getName()).isEqualTo("recorder-chrome-1-abc");
    assertThat(strings(dockerRequest(recorder).get("Env")))
        .contains("DISPLAY_CONTAINER_NAME=10.0.0.2");
    assertThat(strings(hostConfig(recorder).get("Binds")))
        .contains("/opt/selenium/assets/session-1:/videos");
  }
}
