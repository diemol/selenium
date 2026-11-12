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
import static org.openqa.selenium.grid.data.Availability.UP;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.local.GuavaEventBus;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.NodeId;
import org.openqa.selenium.grid.data.NodeRestartedEvent;
import org.openqa.selenium.grid.data.NodeStatus;

/**
 * Tests to verify that LocalGridModel does not cause deadlocks when firing events.
 *
 * <p>The deadlock scenario occurs when:
 *
 * <ol>
 *   <li>Thread A (health check) holds LocalNodeRegistry.lock and calls model.setAvailability()
 *       which needs LocalGridModel.lock
 *   <li>Thread B (purge dead nodes) holds LocalGridModel.lock and fires NodeRemovedEvent, whose
 *       listener calls LocalNodeRegistry.remove() which needs LocalNodeRegistry.lock
 * </ol>
 *
 * <p>The fix is to fire events outside the lock scope in LocalGridModel.
 */
class LocalGridModelDeadlockTest {

  private EventBus events;
  private LocalGridModel model;

  @BeforeEach
  void setUp() {
    events = new GuavaEventBus();
    model = new LocalGridModel(events);
  }

  /**
   * Verifies that NodeRestartedEvent is fired AFTER the lock is released in add() when a node
   * restarts (same URI, different NodeId).
   */
  @Test
  void addShouldFireRestartEventOutsideLock() throws Exception {
    NodeId originalNodeId = new NodeId(UUID.randomUUID());
    NodeId newNodeId = new NodeId(UUID.randomUUID());
    URI sharedUri = new URI("http://localhost:5555");

    // Add the original node
    NodeStatus originalNode = createNodeStatus(originalNodeId, sharedUri, UP);
    model.add(originalNode);
    model.setAvailability(originalNodeId, UP);

    AtomicBoolean canAcquireLock = new AtomicBoolean(false);
    CountDownLatch eventFired = new CountDownLatch(1);

    // When restart event fires, try to call another model method that needs the lock
    events.addListener(
        NodeRestartedEvent.listener(status -> {
          try {
            model.getSnapshot();
            canAcquireLock.set(true);
          } catch (Exception e) {
            canAcquireLock.set(false);
          }
          eventFired.countDown();
        }));

    // Add a new node with same URI but different ID (simulates node restart)
    NodeStatus restartedNode = createNodeStatus(newNodeId, sharedUri, UP);
    model.add(restartedNode);

    // Wait for event to be processed
    boolean eventProcessed = eventFired.await(5, TimeUnit.SECONDS);

    assertThat(eventProcessed).as("Restart event should have been fired").isTrue();
    assertThat(canAcquireLock.get())
        .as(
            "Should be able to acquire lock in event listener, proving restart event fired outside"
                + " lock")
        .isTrue();
  }

  private NodeStatus createNodeStatus(NodeId nodeId, Availability availability) {
    try {
      return createNodeStatus(nodeId, new URI("http://localhost:5555"), availability);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private NodeStatus createNodeStatus(NodeId nodeId, URI uri, Availability availability) {
    return new NodeStatus(
        nodeId,
        uri,
        1,
        Set.of(),
        availability,
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        "1.0",
        Collections.emptyMap());
  }
}
