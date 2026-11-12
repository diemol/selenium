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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.docker.Docker;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpResponse;

@Tag("UnitTests")
class DockerSessionActivitiesImplTest {

  private static DockerSessionActivitiesImpl activities(Supplier<@Nullable Node> node) {
    return new DockerSessionActivitiesImpl(
        new Docker(request -> new HttpResponse()),
        mock(HttpClient.Factory.class),
        node,
        new DockerContainers(List.of(), null, null, "bridge", true, Map.of(), List.of(), Map.of()));
  }

  @Test
  void livenessAsksTheNodeWhoOwnsTheSession() {
    Node node = mock(Node.class);
    when(node.isSessionOwner(new SessionId("session-1"))).thenReturn(true);

    DockerSessionActivitiesImpl activities = activities(() -> node);

    assertThat(activities.isSessionActive("session-1")).isTrue();
    assertThat(activities.isSessionActive("session-2")).isFalse();
  }

  @Test
  void livenessFailsWhileTheNodeIsStarting() {
    // Failing makes Temporal retry, instead of reporting the session as lost.
    DockerSessionActivitiesImpl activities = activities(() -> null);

    assertThatThrownBy(() -> activities.isSessionActive("session-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void logsKeepOnlyThePayloadOfEachDockerFrame() throws IOException {
    ByteArrayOutputStream multiplexed = new ByteArrayOutputStream();
    writeFrame(multiplexed, 1, "INFO: Session started\n");
    writeFrame(multiplexed, 2, "WARN: Something on stderr\n");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DockerSessionActivitiesImpl.parseMultiplexedStream(
        new ByteArrayInputStream(multiplexed.toByteArray()), out);

    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo("INFO: Session started\nWARN: Something on stderr\n");
  }

  @Test
  void serverUrlUsesTheContainerIpWhenNoHostIsSet() {
    DockerSessionSpec spec = new DockerSessionSpec();
    spec.browserPort = 4444;

    assertThat(DockerSessionWorkflowImpl.serverUrl(spec, "10.0.0.2"))
        .isEqualTo("http://10.0.0.2:4444/wd/hub");

    spec.browserHost = "localhost";
    spec.browserPort = 50123;
    assertThat(DockerSessionWorkflowImpl.serverUrl(spec, "10.0.0.2"))
        .isEqualTo("http://localhost:50123/wd/hub");
  }

  /** Docker frame: stream type (1 byte), padding (3 bytes), payload size (4 bytes), payload. */
  private static void writeFrame(ByteArrayOutputStream target, int stream, String payload)
      throws IOException {
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    DataOutputStream frame = new DataOutputStream(target);
    frame.writeByte(stream);
    frame.write(new byte[3]);
    frame.writeInt(bytes.length);
    frame.write(bytes);
    frame.flush();
  }
}
