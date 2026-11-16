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

import static java.util.Optional.ofNullable;
import static org.openqa.selenium.docker.ContainerConfig.image;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.docker.ContainerConfig;
import org.openqa.selenium.docker.Device;
import org.openqa.selenium.docker.Image;
import org.openqa.selenium.docker.Port;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;

/**
 * Builds the browser and recorder containers of a session from its {@link DockerSessionSpec} and
 * this Node's Docker settings. The Activities call it right before they talk to Docker, so the
 * Workflow input only holds plain values.
 */
public class DockerContainers {

  static final int BROWSER_PORT = 4444;
  static final int RECORDER_PORT = 9000;
  private static final Logger LOG = Logger.getLogger(DockerContainers.class.getName());
  private static final long BROWSER_SHM_SIZE = 2147483648L; // 2GB

  private final List<Device> devices;
  private final @Nullable Image videoImage;
  private final @Nullable DockerAssetsPath assetsPath;
  private final String networkName;
  private final boolean runningInDocker;
  private final Map<String, Object> hostConfig;
  private final List<String> hostConfigKeys;
  private final Map<String, String> groupingLabels;

  public DockerContainers(
      List<Device> devices,
      @Nullable Image videoImage,
      @Nullable DockerAssetsPath assetsPath,
      String networkName,
      boolean runningInDocker,
      Map<String, Object> hostConfig,
      List<String> hostConfigKeys,
      Map<String, String> groupingLabels) {
    this.devices = Require.nonNull("Container devices", devices);
    this.videoImage = videoImage;
    this.assetsPath = assetsPath;
    this.networkName = Require.nonNull("Docker network name", networkName);
    this.runningInDocker = runningInDocker;
    this.hostConfig = Require.nonNull("Container host config", hostConfig);
    this.hostConfigKeys = Require.nonNull("Browser container host config keys", hostConfigKeys);
    this.groupingLabels = Require.nonNull("Container grouping labels", groupingLabels);
  }

  /**
   * Tells whether this Node runs inside Docker. Then it reaches containers by their IP, and no
   * ports are mapped to the host.
   *
   * @return true inside Docker
   */
  boolean runningInDocker() {
    return runningInDocker;
  }

  /**
   * Tells whether this Node keeps session assets: capabilities, logs, and video.
   *
   * @return true when an assets folder is configured
   */
  boolean savesAssets() {
    return assetsPath != null;
  }

  /**
   * Returns the folder of one session's assets, as this Node sees it.
   *
   * @param sessionId the WebDriver session id
   * @return the session folder
   */
  Path sessionFolder(String sessionId) {
    return Paths.get(
        Require.nonNull("Assets path", assetsPath).getContainerPath(new SessionId(sessionId)));
  }

  /**
   * Tells whether the session is recorded by a separate recorder container.
   *
   * @param desired the session capabilities
   * @return true when a recorder container is needed
   */
  boolean usesRecorder(Capabilities desired) {
    return assetsPath != null && videoImage != null && recordVideoForSession(desired);
  }

  /**
   * Builds the browser container.
   *
   * @param spec the session spec
   * @param browserImage the image named in the spec
   * @return the container to create
   */
  ContainerConfig browser(DockerSessionSpec spec, Image browserImage) {
    Capabilities desired = new ImmutableCapabilities(spec.desiredCapabilities);
    ContainerConfig browser =
        image(browserImage)
            .env(createBrowserContainerEnvVars(desired))
            .shmMemorySize(BROWSER_SHM_SIZE)
            .network(networkName)
            .devices(devices)
            .applyHostConfig(hostConfig, hostConfigKeys)
            .labels(groupingLabels)
            .name(spec.browserContainerName());
    if (assetsPath != null && videoImage == null && recordVideoForSession(desired)) {
      browser = browser.bind(Collections.singletonMap(assetsPath.getHostPath(), "/videos"));
    }
    if (!runningInDocker) {
      browser = browser.map(Port.tcp(BROWSER_PORT), Port.tcp(spec.browserPort));
    }
    return browser;
  }

  /**
   * Builds the recorder container.
   *
   * @param spec the session spec
   * @param browserIp the browser container IP, which the recorder connects to
   * @param sessionId the WebDriver session id, which names the video folder
   * @param hostPort host port for the recorder, or null inside Docker
   * @return the container to create
   */
  ContainerConfig recorder(
      DockerSessionSpec spec, String browserIp, String sessionId, @Nullable Integer hostPort) {
    Capabilities desired = new ImmutableCapabilities(spec.desiredCapabilities);
    String hostPath = Require.nonNull("Assets path", assetsPath).getHostPath();
    ContainerConfig recorder =
        image(Require.nonNull("Video image", videoImage))
            .env(getVideoContainerEnvVars(desired, browserIp))
            .bind(Collections.singletonMap(Paths.get(hostPath, sessionId).toString(), "/videos"))
            .network(networkName)
            .labels(groupingLabels)
            .name(spec.recorderContainerName());
    if (hostPort != null) {
      recorder = recorder.map(Port.tcp(RECORDER_PORT), Port.tcp(hostPort));
    }
    return recorder;
  }

  Map<String, String> createBrowserContainerEnvVars(Capabilities sessionCapabilities) {
    Map<String, String> envVars = new HashMap<>();
    boolean recordsInline = videoImage == null && recordVideoForSession(sessionCapabilities);
    // Enable env var to trigger video recording if session capabilities request and external video
    // container is disabled
    if (recordsInline) {
      envVars.put("SE_RECORD_VIDEO", "true");
      envVars.put("SE_VIDEO_RECORD_STANDALONE", "true");
    }
    envVars.putAll(getBrowserContainerEnvVars(sessionCapabilities));
    if (recordsInline) {
      // The browser container binds the assets root, so the recorder has to create the session
      // folder itself, and it only does that while it owns the file name. Both are enforced over
      // anything inherited from the Node: a flat layout or a fixed name would scatter every
      // session's video into the assets root.
      String inheritedFileName = envVars.get("SE_VIDEO_FILE_NAME");
      if (inheritedFileName != null && !"auto".equalsIgnoreCase(inheritedFileName)) {
        LOG.warning(
            String.format(
                "Ignoring SE_VIDEO_FILE_NAME '%s' for inline recording so the recorder can name"
                    + " videos per session",
                inheritedFileName));
      }
      envVars.put("SE_VIDEO_SESSION_SUBFOLDER", "true");
      envVars.put("SE_VIDEO_FILE_NAME", "auto");
      if (assetsPath != null) {
        LOG.fine(
            String.format(
                "Inline recording will write to %s/<sessionId>", assetsPath.getHostPath()));
      }
    }
    return envVars;
  }

  Map<String, String> getBrowserContainerEnvVars(Capabilities sessionRequestCapabilities) {
    Map<String, String> envVars = new HashMap<>();
    // Passing env vars set to the child container
    setEnvVarsToContainer(envVars);
    // Capabilities set to env vars with higher precedence
    setCapsToEnvVars(sessionRequestCapabilities, envVars);
    return envVars;
  }

  private void setEnvVarsToContainer(Map<String, String> envVars) {
    Map<String, String> seEnvVars = System.getenv();
    seEnvVars.entrySet().stream()
        .filter(entry ->
            entry.getKey().startsWith("SE_") || entry.getKey().equalsIgnoreCase("LANGUAGE"))
        .forEach(entry -> envVars.put(entry.getKey(), entry.getValue()));
  }

  private void setCapsToEnvVars(
      Capabilities sessionRequestCapabilities, Map<String, String> envVars) {
    Optional<Dimension> screenResolution =
        ofNullable(getScreenResolution(sessionRequestCapabilities));
    screenResolution.ifPresent(dimension -> {
      envVars.put("SE_SCREEN_WIDTH", String.valueOf(dimension.getWidth()));
      envVars.put("SE_SCREEN_HEIGHT", String.valueOf(dimension.getHeight()));
    });
    Optional<TimeZone> timeZone = ofNullable(getTimeZone(sessionRequestCapabilities));
    timeZone.ifPresent(zone -> envVars.put("TZ", zone.getID()));
  }

  Map<String, String> getVideoContainerEnvVars(
      Capabilities sessionRequestCapabilities, String containerIp) {
    Map<String, String> envVars = getVideoContainerEnvVars(sessionRequestCapabilities);
    envVars.put("DISPLAY_CONTAINER_NAME", containerIp);
    return envVars;
  }

  Map<String, String> getVideoContainerEnvVars(Capabilities sessionRequestCapabilities) {
    Map<String, String> envVars = new HashMap<>();
    // Passing env vars set to the child container
    setEnvVarsToContainer(envVars);
    // Capabilities set to env vars with higher precedence
    setCapsToEnvVars(sessionRequestCapabilities, envVars);
    Optional<String> videoName =
        ofNullable(getVideoFileName(sessionRequestCapabilities, "se:videoName"))
            .or(() -> ofNullable(getVideoFileName(sessionRequestCapabilities, "se:name")));
    videoName.ifPresent(name -> envVars.put("SE_VIDEO_FILE_NAME", String.format("%s.mp4", name)));
    // The video container's bind mount is already per-session (assets/<sessionId> -> /videos), so
    // the recorder must not nest a second session folder inside it. Blanking the value stops a
    // Node-level setting from passing through and leaves the image default in charge.
    envVars.put("SE_VIDEO_SESSION_SUBFOLDER", "");
    return envVars;
  }

  @Nullable
  private String getVideoFileName(Capabilities sessionRequestCapabilities, String capabilityName) {
    Optional<Object> testName =
        ofNullable(sessionRequestCapabilities.getCapability(capabilityName));
    if (testName.isPresent()) {
      String name = testName.get().toString();
      if (!name.isEmpty()) {
        name = name.replaceAll(" ", "_").replaceAll("[^a-zA-Z0-9_-]", "");
        if (name.length() > 251) {
          name = name.substring(0, 251);
        }
        return name;
      }
    }
    return null;
  }

  @Nullable
  private TimeZone getTimeZone(Capabilities sessionRequestCapabilities) {
    Optional<Object> timeZone = ofNullable(sessionRequestCapabilities.getCapability("se:timeZone"));
    if (timeZone.isPresent()) {
      String tz = timeZone.get().toString();
      if (List.of(TimeZone.getAvailableIDs()).contains(tz)) {
        return TimeZone.getTimeZone(tz);
      }
    }
    String envTz = System.getenv("TZ");
    if (envTz != null && List.of(TimeZone.getAvailableIDs()).contains(envTz)) {
      return TimeZone.getTimeZone(envTz);
    }
    return null;
  }

  @Nullable
  private Dimension getScreenResolution(Capabilities sessionRequestCapabilities) {
    Optional<Object> screenResolution =
        ofNullable(sessionRequestCapabilities.getCapability("se:screenResolution"));
    if (screenResolution.isEmpty()) {
      return null;
    }
    try {
      String[] resolution = screenResolution.get().toString().split("x");
      int screenWidth = Integer.parseInt(resolution[0]);
      int screenHeight = Integer.parseInt(resolution[1]);
      if (screenWidth > 0 && screenHeight > 0) {
        return new Dimension(screenWidth, screenHeight);
      } else {
        LOG.warning(
            "One of the values provided for screenResolution is negative, "
                + "defaults will be used. Received value: "
                + screenResolution);
      }
    } catch (Exception e) {
      LOG.warning(
          "Values provided for screenResolution are not valid integers or "
              + "either width or height are missing, defaults will be used."
              + "Received value: "
              + screenResolution);
    }
    return null;
  }

  private boolean recordVideoForSession(Capabilities sessionRequestCapabilities) {
    Optional<Object> recordVideo =
        ofNullable(sessionRequestCapabilities.getCapability("se:recordVideo"));
    return recordVideo.isPresent() && Boolean.parseBoolean(recordVideo.get().toString());
  }
}
