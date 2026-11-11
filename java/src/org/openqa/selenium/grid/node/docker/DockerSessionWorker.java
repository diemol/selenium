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

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.openqa.selenium.docker.Docker;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.remote.http.HttpClient;

/** Connects the Docker Node to Temporal and starts its Worker. */
final class DockerSessionWorker {

  private static final Logger LOG = Logger.getLogger(DockerSessionWorker.class.getName());
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private DockerSessionWorker() {
    // Static utility.
  }

  static DockerSessionRunner start(
      TemporalOptions options,
      Docker docker,
      HttpClient.Factory clientFactory,
      DockerContainers containers,
      Supplier<@Nullable Node> node) {
    String target = options.getTarget();
    WorkflowServiceStubsOptions.Builder stubs =
        WorkflowServiceStubsOptions.newBuilder().setTarget(target);
    // Temporal Cloud: TLS plus the API key on every call. A local server needs neither.
    options.getApiKey().ifPresent(apiKey -> stubs.setEnableHttps(true).addApiKey(() -> apiKey));
    WorkflowServiceStubs service;
    try {
      service = WorkflowServiceStubs.newConnectedServiceStubs(stubs.build(), CONNECT_TIMEOUT);
    } catch (RuntimeException e) {
      throw new ConfigException(
          "Unable to reach the Temporal Service at %s. The Docker Node needs it to manage"
              + " sessions. For local use, run `temporal server start-dev`. For Temporal Cloud,"
              + " set --temporal-target, --temporal-namespace and the %s environment variable."
              + " Cause: %s",
          target, TemporalOptions.API_KEY_ENV, e.getMessage());
    }

    WorkflowClient client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder().setNamespace(options.getNamespace()).build());

    String taskQueue = options.getTaskQueue();
    WorkerFactory factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(DockerSessionWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new DockerSessionActivitiesImpl(docker, clientFactory, node, containers));
    factory.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  factory.shutdown();
                  service.shutdown();
                },
                "temporal-worker-shutdown"));

    LOG.info(
        String.format(
            "Temporal Worker started (target %s, namespace %s, task queue %s, %s)",
            target,
            options.getNamespace(),
            taskQueue,
            options.getApiKey().isPresent() ? "API key" : "no authentication"));
    return new DockerSessionRunner(client, taskQueue);
  }
}
