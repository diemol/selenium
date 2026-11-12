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
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.openqa.selenium.docker.Docker;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.temporal.TemporalOptions;
import org.openqa.selenium.remote.http.HttpClient;

/** Connects the Docker Node to Temporal and starts its Worker. */
final class DockerSessionWorker {

  private static final Logger LOG = Logger.getLogger(DockerSessionWorker.class.getName());

  private DockerSessionWorker() {
    // Static utility.
  }

  static DockerSessionRunner start(
      TemporalOptions options,
      Docker docker,
      HttpClient.Factory clientFactory,
      DockerContainers containers,
      Supplier<@Nullable Node> node) {
    WorkflowClient client = options.connect();

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
                  client.getWorkflowServiceStubs().shutdown();
                },
                "temporal-worker-shutdown"));

    LOG.info(
        String.format(
            "Temporal Worker started (target %s, namespace %s, task queue %s, %s)",
            options.getTarget(),
            options.getNamespace(),
            taskQueue,
            options.getApiKey().isPresent() ? "API key" : "no authentication"));
    return new DockerSessionRunner(client, taskQueue);
  }
}
