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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.openqa.selenium.grid.data.Availability.DOWN;

import io.temporal.failure.ApplicationFailure;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.local.GuavaEventBus;
import org.openqa.selenium.grid.data.NodeRemovedEvent;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.node.local.LocalNode;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.temporal.NodeMembershipSpec;
import org.openqa.selenium.grid.testing.PassthroughHttpClient;
import org.openqa.selenium.grid.testing.TestSessionFactory;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.DefaultTestTracer;
import org.openqa.selenium.remote.tracing.Tracer;

class NodeMembershipActivitiesImplTest {

  private final Secret registrationSecret = new Secret("bavarian smoked");
  private final URI uri = URI.create("http://localhost:5555");
  private final Tracer tracer = DefaultTestTracer.createTracer();
  private final EventBus bus = new GuavaEventBus();
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private LocalNodeRegistry registry;

  @AfterEach
  void tearDown() {
    if (registry != null) {
      registry.close();
    }
    executor.shutdownNow();
    bus.close();
  }

  @Test
  void addsANodeThatUsesTheSameSecret() {
    LocalNode node = node(registrationSecret);
    NodeMembershipActivitiesImpl activities = activities(node);

    activities.addNode(spec(node));
    // A second call, as on every heartbeat, keeps the same Node.
    activities.addNode(spec(node));

    assertThat(registry.getNode(node.getId())).isNotNull();
    assertThat(registry.getUpNodeCount()).isEqualTo(1);
  }

  @Test
  void rejectsANodeWithADifferentSecret() {
    // Today the event bus drops this Node's events, and nobody says why.
    LocalNode node = node(new Secret("cheddar"));
    NodeMembershipActivitiesImpl activities = activities(node);

    assertThatThrownBy(() -> activities.addNode(spec(node)))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("registration secret is different")
        .satisfies(e -> assertThat(((ApplicationFailure) e).isNonRetryable()).isTrue());
    assertThat(registry.getNode(node.getId())).isNull();
  }

  @Test
  void marksDownAndRemovesTheNode() {
    LocalNode node = node(registrationSecret);
    NodeMembershipActivitiesImpl activities = activities(node);
    AtomicReference<NodeStatus> removed = new AtomicReference<>();
    bus.addListener(NodeRemovedEvent.listener(removed::set));
    activities.addNode(spec(node));

    activities.markNodeDown(spec(node));
    assertThat(registry.getStatus().getNodes())
        .singleElement()
        .extracting(NodeStatus::getAvailability)
        .isEqualTo(DOWN);

    activities.removeNode(spec(node));
    assertThat(registry.getNode(node.getId())).isNull();
    assertThat(removed.get().getNodeId()).isEqualTo(node.getId());
  }

  private LocalNode node(Secret secret) {
    return LocalNode.builder(tracer, bus, uri, uri, secret)
        .add(
            new ImmutableCapabilities("browserName", "cheese"),
            new TestSessionFactory((id, caps) ->
                new Session(id, uri, new ImmutableCapabilities(), caps, Instant.now())))
        .build();
  }

  private NodeMembershipActivitiesImpl activities(LocalNode node) {
    HttpClient.Factory clientFactory = new PassthroughHttpClient.Factory(node);
    registry =
        new LocalNodeRegistry(
            tracer,
            bus,
            clientFactory,
            registrationSecret,
            Duration.ofHours(1),
            executor,
            Duration.ZERO,
            executor);
    registry.stopRegisteringFromEvents();
    return new NodeMembershipActivitiesImpl(registry, bus, clientFactory, registrationSecret);
  }

  private NodeMembershipSpec spec(LocalNode node) {
    NodeMembershipSpec spec = new NodeMembershipSpec();
    spec.nodeId = node.getId().toString();
    spec.nodeUri = uri.toString();
    spec.heartbeatPeriodSeconds = 60;
    spec.registerPeriodSeconds = 120;
    return spec;
  }
}
