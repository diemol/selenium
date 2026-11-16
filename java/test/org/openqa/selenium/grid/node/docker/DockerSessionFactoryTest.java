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
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.docker.Image;
import org.openqa.selenium.docker.ImageId;
import org.openqa.selenium.docker.internal.ImageSummary;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

@Tag("UnitTests")
class DockerSessionFactoryTest {

  private static final Capabilities RECORDING_CAPS =
      new ImmutableCapabilities("browserName", "chrome", "se:recordVideo", true);

  private static Image image(String name) {
    return new Image(new ImageSummary(new ImageId("sha256:" + name.hashCode()), List.of(name)));
  }

  private static DockerSessionFactory factory(@Nullable Image videoImage) {
    DockerAssetsPath assetsPath =
        new DockerAssetsPath("/opt/selenium/assets", "/opt/selenium/assets");
    DockerContainers containers =
        new DockerContainers(
            List.of(), videoImage, assetsPath, "grid-network", true, Map.of(), List.of(), Map.of());
    return new DockerSessionFactory(
        mock(Tracer.class),
        mock(HttpClient.Factory.class),
        Duration.ofMinutes(5),
        Duration.ofSeconds(120),
        mock(DockerSessionRunner.class),
        containers,
        URI.create("http://localhost:2375"),
        image("selenium/standalone-chrome"),
        new ImmutableCapabilities("browserName", "chrome"),
        caps -> true,
        Duration.ofSeconds(10));
  }

  @Test
  void specUsesOneIdentifierForBothContainersAndTheWorkflow() {
    DockerSessionSpec spec = factory(image("selenium/video")).createSpec(RECORDING_CAPS);

    assertThat(spec.sessionIdentifier).startsWith("chrome-");
    assertThat(spec.browserContainerName()).isEqualTo("browser-" + spec.sessionIdentifier);
    assertThat(spec.recorderContainerName()).isEqualTo("recorder-" + spec.sessionIdentifier);
    assertThat(spec.browserImage).isEqualTo("selenium/standalone-chrome");
    assertThat(spec.recordVideo).isTrue();
    assertThat(spec.saveAssets).isTrue();
  }

  @Test
  void specInsideDockerReachesTheBrowserByContainerIp() {
    DockerSessionSpec spec =
        factory(null).createSpec(new ImmutableCapabilities("browserName", "chrome"));

    assertThat(spec.browserHost).isNull();
    assertThat(spec.browserPort).isEqualTo(4444);
    assertThat(spec.recordVideo).isFalse();
  }

  @Test
  void inlineRecordingNeedsNoRecorderContainer() {
    DockerSessionSpec spec = factory(null).createSpec(RECORDING_CAPS);

    assertThat(spec.recordVideo).isFalse();
  }
}
