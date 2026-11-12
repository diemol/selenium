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

import java.util.Optional;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.net.HostIdentifier;

/**
 * Where the Docker Node finds its Temporal Service. A local server needs only the target. Temporal
 * Cloud also needs its namespace and an API key, read from the {@code TEMPORAL_API_KEY} environment
 * variable so it never shows in a config file or the process list.
 */
public class TemporalOptions {

  static final String TEMPORAL_SECTION = "temporal";
  static final String DEFAULT_TARGET = "127.0.0.1:7233";
  static final String DEFAULT_NAMESPACE = "default";
  static final String API_KEY_ENV = "TEMPORAL_API_KEY";

  private final Config config;

  public TemporalOptions(Config config) {
    this.config = Require.nonNull("Config", config);
  }

  public String getTarget() {
    return config.get(TEMPORAL_SECTION, "target").orElse(DEFAULT_TARGET);
  }

  public String getNamespace() {
    return config.get(TEMPORAL_SECTION, "namespace").orElse(DEFAULT_NAMESPACE);
  }

  /**
   * Returns the API key for Temporal Cloud.
   *
   * @return the key, or empty for a server without authentication, such as a local one
   */
  public Optional<String> getApiKey() {
    return Optional.ofNullable(System.getenv(API_KEY_ENV)).filter(key -> !key.isBlank());
  }

  /**
   * The task queue must stay the same across restarts of this Node, so the Workflows of sessions
   * that were running before a crash find the Node again and clean up. It must also be different
   * for every Node, because the Activities run Docker commands on the Node's own host.
   *
   * @return the task queue for this Node
   */
  public String getTaskQueue() {
    return config
        .get(TEMPORAL_SECTION, "task-queue")
        .orElseGet(() -> "selenium-docker-node-" + HostIdentifier.getHostName());
  }
}
