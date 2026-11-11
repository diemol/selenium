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

/**
 * What {@link DockerSessionWorkflow#waitForStart()} returns to the Node. The {@code createSession}
 * Activity fills in the session fields, and the Workflow marks the result as created.
 */
public class DockerSessionStartResult {

  /** How session creation ended. */
  public enum Outcome {
    /** The session is running. */
    CREATED,
    /** The container side failed. Another Node, or a later attempt, may succeed. */
    RETRY,
    /** The browser refused the session, so retrying the same request will not help. */
    NOT_CREATED
  }

  public Outcome outcome = Outcome.RETRY;
  public String message = "";
  public String sessionId = "";
  public Map<String, Object> capabilities = new HashMap<>();
  public String dialect = "";
  public String url = "";
  public boolean recording;

  DockerSessionStartResult created(String url, boolean recording) {
    this.outcome = Outcome.CREATED;
    this.url = url;
    this.recording = recording;
    return this;
  }

  static DockerSessionStartResult failed(Outcome outcome, String message) {
    DockerSessionStartResult result = new DockerSessionStartResult();
    result.outcome = outcome;
    result.message = message;
    return result;
  }
}
