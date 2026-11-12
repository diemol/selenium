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

import io.temporal.activity.ActivityInterface;
import java.util.Map;

/**
 * Side effects of a Docker session. Each method is safe to call again with the same input, because
 * Temporal may retry it after a failure or a lost reply.
 */
@ActivityInterface
public interface DockerSessionActivities {

  /**
   * Creates the browser container, or returns the existing one with the same name.
   *
   * @param spec the session spec
   * @return the container id
   */
  String createBrowserContainer(DockerSessionSpec spec);

  /**
   * Starts the container.
   *
   * @param name the container name
   * @return the container IP address
   */
  String startContainer(String name);

  /**
   * Polls {@code url + /status} until it answers 200, sending a heartbeat on each attempt.
   *
   * @param url the server base URL
   * @param timeoutSeconds how long to keep polling
   */
  void waitForServer(String url, long timeoutSeconds);

  /**
   * Creates the WebDriver session in the browser container.
   *
   * @param spec the session spec, for the desired capabilities and timeouts
   * @param url the browser server URL
   * @param containerIp the browser container IP, used for the CDP forwarding endpoint
   * @return the session id, capabilities, and dialect. The Workflow fills in the rest.
   */
  DockerSessionStartResult createSession(DockerSessionSpec spec, String url, String containerIp);

  /**
   * Writes the session capabilities to the session assets folder. Failures are only logged.
   *
   * @param spec the session spec, for the assets folder
   * @param sessionId the WebDriver session id
   * @param capabilities the capabilities to save
   */
  void saveCapabilities(DockerSessionSpec spec, String sessionId, Map<String, Object> capabilities);

  /**
   * Creates and starts the recorder container and waits until it is ready.
   *
   * @param spec the session spec, for the recorder container
   * @param browserIp the browser container IP, which the recorder connects to
   * @param sessionId the WebDriver session id, which names the video folder
   */
  void startRecorder(DockerSessionSpec spec, String browserIp, String sessionId);

  /**
   * Copies the browser container logs to the session assets folder.
   *
   * @param spec the session spec, for the container name and assets folder
   * @param sessionId the WebDriver session id
   */
  void saveLogs(DockerSessionSpec spec, String sessionId);

  /**
   * Stops a container. A missing or stopped container is not an error.
   *
   * @param name the container name
   * @param gracePeriodSeconds how long Docker waits before it kills the container
   */
  void stopContainer(String name, long gracePeriodSeconds);

  /**
   * Removes a container. A missing container is not an error.
   *
   * @param name the container name
   */
  void removeContainer(String name);

  /**
   * Tells whether the Grid Node still has the session. After a Node restart the answer is false, so
   * the Workflow removes the containers that nobody uses any more. Fails while the Node is still
   * starting.
   *
   * @param sessionId the WebDriver session id
   * @return true while this Node process serves the session
   */
  boolean isSessionActive(String sessionId);
}
