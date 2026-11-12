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

package org.openqa.selenium.grid.distributor.local;

import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static org.openqa.selenium.grid.data.Availability.DOWN;
import static org.openqa.selenium.grid.data.Availability.UP;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

import io.temporal.failure.ApplicationFailure;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeRemovedEvent;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.distributor.NodeRegistry;
import org.openqa.selenium.grid.security.AddSecretFilter;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.temporal.NodeMembershipActivities;
import org.openqa.selenium.grid.temporal.NodeMembershipSpec;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

/**
 * Each check throws with a message that says what is wrong. Temporal records it in the Workflow
 * history, and the Node logs it when it is the final answer.
 */
class NodeMembershipActivitiesImpl implements NodeMembershipActivities {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Json JSON = new Json();

  private final NodeRegistry registry;
  private final EventBus bus;
  private final HttpClient.Factory clientFactory;
  private final Secret registrationSecret;

  NodeMembershipActivitiesImpl(
      NodeRegistry registry,
      EventBus bus,
      HttpClient.Factory clientFactory,
      Secret registrationSecret) {
    this.registry = Require.nonNull("Node registry", registry);
    this.bus = Require.nonNull("Event bus", bus);
    this.clientFactory = Require.nonNull("HTTP client factory", clientFactory);
    this.registrationSecret = Require.nonNull("Registration secret", registrationSecret);
  }

  @Override
  public void addNode(NodeMembershipSpec spec) {
    NodeId id = nodeId(spec);
    if (registry.getNode(id) != null) {
      return;
    }

    NodeStatus status;
    ClientConfig config =
        ClientConfig.defaultConfig()
            .baseUri(URI.create(spec.nodeUri))
            .connectionTimeout(TIMEOUT)
            .readTimeout(TIMEOUT);
    try (HttpClient client = clientFactory.createClient(config)) {
      // Any call that needs the secret will do. Asking about a random session changes nothing.
      HttpResponse owner =
          client
              .with(new AddSecretFilter(registrationSecret))
              .execute(new HttpRequest(GET, "/se/grid/node/owner/" + UUID.randomUUID()));
      if (owner.getStatus() == HTTP_UNAUTHORIZED) {
        throw ApplicationFailure.newNonRetryableFailure(
            "The Node's registration secret is different from the Distributor's", "SecretMismatch");
      }
      HttpResponse response = client.execute(new HttpRequest(GET, "/se/grid/node/status"));
      status = JSON.toType(Contents.string(response), NodeStatus.class);
    }

    if (!id.equals(status.getNodeId())) {
      throw ApplicationFailure.newNonRetryableFailure(
          String.format("Another Node (%s) answers at %s", status.getNodeId(), spec.nodeUri),
          "WrongNode");
    }
    if (status.getAvailability() != UP) {
      // Retried: a Node that is still starting becomes UP soon.
      throw ApplicationFailure.newFailure(
          "The Node is " + status.getAvailability() + ", not UP", "NodeNotUp");
    }
    registry.register(status);
    if (registry.getNode(id) == null) {
      throw ApplicationFailure.newFailure(
          "The Distributor did not add the Node. Check the Distributor log.", "NotAdded");
    }
  }

  @Override
  public void markNodeDown(NodeMembershipSpec spec) {
    registry.updateNodeAvailability(URI.create(spec.nodeUri), nodeId(spec), DOWN);
  }

  @Override
  public void removeNode(NodeMembershipSpec spec) {
    NodeId id = nodeId(spec);
    registry.getStatus().getNodes().stream()
        .filter(node -> node.getNodeId().equals(id))
        .findFirst()
        // Same as the old purge: the Session Map drops the Node's sessions on this event.
        .ifPresent(node -> bus.fire(new NodeRemovedEvent(node)));
    registry.remove(id);
  }

  private static NodeId nodeId(NodeMembershipSpec spec) {
    return new NodeId(UUID.fromString(spec.nodeId));
  }
}
