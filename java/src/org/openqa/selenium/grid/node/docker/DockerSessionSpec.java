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

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Input of {@link DockerSessionWorkflow}. Temporal stores it as JSON in the Workflow history and
 * hands the same value back when the Workflow is replayed, so it only holds plain values. The
 * Activities build the containers from it with {@link DockerContainers}.
 */
public class DockerSessionSpec {

  /** Unique per session request. Used in container names and in the Workflow id. */
  public String sessionIdentifier = "";

  public Map<String, Object> desiredCapabilities = new HashMap<>();

  /** Name of the browser image. */
  public String browserImage = "";

  /** Port where the Node reaches the browser container's server. */
  public int browserPort;

  /** Host where the Node reaches the browser container. Null means the container's own IP. */
  public @Nullable String browserHost;

  /** True when a separate recorder container records the session. */
  public boolean recordVideo;

  /** True when this Node keeps session assets: capabilities, logs, and video. */
  public boolean saveAssets;

  public long serverStartTimeoutSeconds;

  public long sessionTimeoutSeconds;

  public long stopGracePeriodSeconds;

  static String workflowId(String sessionIdentifier) {
    return "docker-session-" + sessionIdentifier;
  }

  String browserContainerName() {
    return "browser-" + sessionIdentifier;
  }

  String recorderContainerName() {
    return "recorder-" + sessionIdentifier;
  }
}
