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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.time.Duration;
import java.util.Optional;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.net.HostIdentifier;

/**
 * Where Grid finds its Temporal Service. A local server needs only the target. Temporal Cloud also
 * needs its namespace and an API key, read from the {@code TEMPORAL_API_KEY} environment variable
 * so it never shows in a config file or the process list.
 */
public class TemporalOptions {

  static final String TEMPORAL_SECTION = "temporal";
  static final String DEFAULT_TARGET = "127.0.0.1:7233";
  static final String DEFAULT_NAMESPACE = "default";
  static final String API_KEY_ENV = "TEMPORAL_API_KEY";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private final Config config;

  public TemporalOptions(Config config) {
    this.config = Require.nonNull("Config", config);
  }

  /**
   * Nodes and the Distributor must agree on this: a Node that registers through Temporal needs a
   * Distributor that runs the {@link NodeMembershipWorkflow} Worker.
   *
   * @return true when Nodes register through Temporal instead of the event bus
   */
  public boolean isNodeRegistrationEnabled() {
    return config.getBool(TEMPORAL_SECTION, "node-registration").orElse(false);
  }

  /**
   * Connects to the Temporal Service. Callers shut down the connection with {@code
   * client.getWorkflowServiceStubs().shutdown()}.
   *
   * @return a client for the configured namespace
   */
  public WorkflowClient connect() {
    String target = getTarget();
    WorkflowServiceStubsOptions.Builder stubs =
        WorkflowServiceStubsOptions.newBuilder().setTarget(target);
    // Temporal Cloud: TLS plus the API key on every call. A local server needs neither.
    getApiKey().ifPresent(apiKey -> stubs.setEnableHttps(true).addApiKey(() -> apiKey));
    WorkflowServiceStubs service;
    try {
      service = WorkflowServiceStubs.newConnectedServiceStubs(stubs.build(), CONNECT_TIMEOUT);
    } catch (RuntimeException e) {
      throw new ConfigException(
          "Unable to reach the Temporal Service at %s. For local use, run `temporal server"
              + " start-dev`. For Temporal Cloud, set --temporal-target, --temporal-namespace and"
              + " the %s environment variable. Cause: %s",
          target, API_KEY_ENV, e.getMessage());
    }
    return WorkflowClient.newInstance(
        service, WorkflowClientOptions.newBuilder().setNamespace(getNamespace()).build());
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
