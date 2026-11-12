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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.WebSocket;
import org.openqa.selenium.remote.tracing.empty.NullTracer;

@Tag("UnitTests")
class DockerSessionTest {

  private final List<String> events = new ArrayList<>();

  private DockerSession createSession(Runnable removeContainers) throws Exception {
    HttpClient client =
        new HttpClient() {
          @Override
          public HttpResponse execute(HttpRequest req) {
            return new HttpResponse();
          }

          @Override
          public WebSocket openSocket(HttpRequest request, WebSocket.Listener listener) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close() {
            events.add("client closed");
          }
        };
    return new DockerSession(
        removeContainers,
        new NullTracer(),
        client,
        new SessionId("test-session-id"),
        new URL("http://localhost:4444"),
        new ImmutableCapabilities(),
        new ImmutableCapabilities(),
        Dialect.W3C,
        Dialect.W3C,
        Instant.now());
  }

  @Test
  void stopAsksTheWorkflowToRemoveTheContainersBeforeClosingTheClient() throws Exception {
    DockerSession session = createSession(() -> events.add("containers removed"));

    session.stop();

    assertThat(events).containsExactly("containers removed", "client closed");
  }

  @Test
  void stopClosesTheClientEvenIfTheCleanupRequestFails() throws Exception {
    DockerSession session =
        createSession(() -> {
          throw new IllegalStateException("Temporal unreachable");
        });

    assertThatThrownBy(session::stop).isInstanceOf(IllegalStateException.class);
    assertThat(events).containsExactly("client closed");
  }

  @Test
  void fileCommandsAreForwardedToTheContainer() throws Exception {
    assertThat(createSession(() -> {}).isRemoteFileSystem()).isTrue();
  }
}
