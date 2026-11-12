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

import static java.util.logging.Level.FINE;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.PersistentCapabilities;
import org.openqa.selenium.UsernameAndPassword;
import org.openqa.selenium.docker.Container;
import org.openqa.selenium.docker.ContainerConfig;
import org.openqa.selenium.docker.ContainerId;
import org.openqa.selenium.docker.ContainerInfo;
import org.openqa.selenium.docker.Docker;
import org.openqa.selenium.docker.DockerException;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.SessionFactory;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.remote.Command;
import org.openqa.selenium.remote.DriverCommand;
import org.openqa.selenium.remote.ProtocolHandshake;
import org.openqa.selenium.remote.Response;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

/** Runs on the Node that owns the containers, through the Node's own task queue. */
public class DockerSessionActivitiesImpl implements DockerSessionActivities {

  private static final Logger LOG = Logger.getLogger(DockerSessionActivitiesImpl.class.getName());
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final Docker docker;
  private final HttpClient.Factory clientFactory;
  private final Supplier<@Nullable Node> node;
  private final DockerContainers containers;

  public DockerSessionActivitiesImpl(
      Docker docker,
      HttpClient.Factory clientFactory,
      Supplier<@Nullable Node> node,
      DockerContainers containers) {
    this.docker = Require.nonNull("Docker", docker);
    this.containers = Require.nonNull("Docker containers", containers);
    this.clientFactory = Require.nonNull("HTTP client factory", clientFactory);
    this.node = Require.nonNull("Node", node);
  }

  @Override
  public String createBrowserContainer(DockerSessionSpec spec) {
    return createContainer(containers.browser(spec, docker.getImage(spec.browserImage)));
  }

  private String createContainer(ContainerConfig config) {
    // A retry after a lost reply finds the container from the first attempt instead of failing
    // on the name conflict.
    String name = Require.nonNull("Container name", config.getName());
    Optional<ContainerInfo> existing = docker.inspect(new ContainerId(name));
    if (existing.isPresent()) {
      LOG.info("Container already exists: " + name);
      return existing.get().getId().toString();
    }
    return docker.create(config).getId().toString();
  }

  @Override
  public String startContainer(String name) {
    Container container = docker.getContainer(new ContainerId(name));
    container.start();
    return container.inspect().getIp();
  }

  @Override
  public void waitForServer(String url, long timeoutSeconds) {
    HttpClient client = clientFactory.createClient(withBasicAuth(clientConfig(url)));
    try {
      Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
      int attempt = 0;
      while (Instant.now().isBefore(deadline)) {
        Activity.getExecutionContext().heartbeat(++attempt);
        if (isServerUp(client)) {
          return;
        }
        sleep(POLL_INTERVAL);
      }
      throw ApplicationFailure.newNonRetryableFailure(
          String.format("Server at %s did not start within %d seconds", url, timeoutSeconds),
          "ServerStartTimeout");
    } finally {
      client.close();
    }
  }

  @Override
  public DockerSessionStartResult createSession(
      DockerSessionSpec spec, String url, String containerIp) {
    ClientConfig config =
        withBasicAuth(
            clientConfig(url).readTimeout(Duration.ofSeconds(spec.sessionTimeoutSeconds)));
    HttpClient client = clientFactory.createClient(config);
    try {
      Capabilities desired = new ImmutableCapabilities(spec.desiredCapabilities);
      Command command =
          new Command(
              null, DriverCommand.NEW_SESSION(SessionFactory.stripPerHopCapabilities(desired)));
      ProtocolHandshake.Result result = new ProtocolHandshake().createSession(client, command);
      Response response = result.createResponse();

      String sessionId = response.getSessionId();
      Capabilities returned = new ImmutableCapabilities((Map<?, ?>) response.getValue());
      Capabilities merged = desired.merge(returned);
      // The Node and the browser run on different hosts, so CDP connections are forwarded to the
      // container that runs the browser.
      String forwardCdp =
          String.format("ws://%s:%s/session/%s/se/fwd", containerIp, spec.browserPort, sessionId);
      merged = new PersistentCapabilities(merged).setCapability("se:forwardCdp", forwardCdp);

      DockerSessionStartResult session = new DockerSessionStartResult();
      session.sessionId = sessionId;
      session.capabilities = merged.asMap();
      session.dialect = result.getDialect().name();
      return session;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      client.close();
    }
  }

  @Override
  public void saveCapabilities(
      DockerSessionSpec spec, String sessionId, Map<String, Object> capabilities) {
    Path folder = containers.sessionFolder(sessionId);
    try {
      Files.createDirectories(folder);
      Files.writeString(
          folder.resolve("sessionCapabilities.json"),
          new Json().toJson(capabilities),
          Charset.defaultCharset());
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to save session capabilities", e);
    }
  }

  @Override
  public void startRecorder(DockerSessionSpec spec, String browserIp, String sessionId) {
    // One attempt only, so picking a free port here is safe.
    Integer hostPort = containers.runningInDocker() ? null : PortProber.findFreePort();
    String name = spec.recorderContainerName();

    createContainer(containers.recorder(spec, browserIp, sessionId, hostPort));
    String recorderIp = startContainer(name);
    String host = containers.runningInDocker() ? recorderIp : "localhost";
    int port = hostPort != null ? hostPort : DockerContainers.RECORDER_PORT;
    waitForServer(String.format("http://%s:%d", host, port), spec.serverStartTimeoutSeconds);
    LOG.info("Video container started: " + name);
  }

  @Override
  public void saveLogs(DockerSessionSpec spec, String sessionId) {
    ContainerId id = new ContainerId(spec.browserContainerName());
    if (docker.inspect(id).isEmpty()) {
      LOG.log(FINE, () -> "Skip saving logs because the container is gone: " + id);
      return;
    }
    Path folder = containers.sessionFolder(sessionId);
    Path logFile = folder.resolve("selenium-server.log");
    try {
      Files.createDirectories(folder);
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(logFile))) {
        parseMultiplexedStream(docker.getContainer(id).getLogs().getLogs(), out);
      }
      LOG.log(FINE, () -> String.format("Saved container %s logs to %s", id, logFile));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void stopContainer(String name, long gracePeriodSeconds) {
    ContainerId id = new ContainerId(name);
    if (docker.inspect(id).isEmpty()) {
      return;
    }
    try {
      docker.getContainer(id).stop(Duration.ofSeconds(gracePeriodSeconds));
    } catch (DockerException e) {
      // AutoRemove can delete the container while the stop call is still running.
      if (docker.inspect(id).isPresent()) {
        throw e;
      }
    }
  }

  @Override
  public void removeContainer(String name) {
    docker.getContainer(new ContainerId(name)).remove();
  }

  @Override
  public boolean isSessionActive(String sessionId) {
    Node current = node.get();
    if (current == null) {
      // The Worker starts before the Node exists. Failing makes Temporal try again later.
      throw new IllegalStateException("The Node is still starting");
    }
    return current.isSessionOwner(new SessionId(sessionId));
  }

  private static boolean isServerUp(HttpClient client) {
    try {
      HttpResponse response = client.execute(new HttpRequest(GET, "/status"));
      if (response.getStatus() == 401) {
        LOG.warning(
            "Server requires basic authentication. Set SE_ROUTER_USERNAME and SE_ROUTER_PASSWORD"
                + " environment variables to provide credentials.");
      }
      return response.getStatus() == 200;
    } catch (UncheckedIOException e) {
      return false;
    }
  }

  private static ClientConfig clientConfig(String url) {
    try {
      return ClientConfig.defaultConfig().baseUrl(new URL(url));
    } catch (MalformedURLException e) {
      throw ApplicationFailure.newNonRetryableFailureWithCause(
          "Invalid server URL: " + url, "InvalidUrl", e);
    }
  }

  static ClientConfig withBasicAuth(ClientConfig config) {
    String username = System.getenv("SE_ROUTER_USERNAME");
    String password = System.getenv("SE_ROUTER_PASSWORD");
    if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
      return config.authenticateAs(new UsernameAndPassword(username, password));
    }
    return config;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the server", e);
    }
  }

  /** Docker logs come as frames: 1 stream-type byte, 3 padding bytes, 4 size bytes, payload. */
  @SuppressWarnings("InfiniteLoopStatement")
  static void parseMultiplexedStream(InputStream stream, OutputStream out) throws IOException {
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
      while (true) {
        in.readFully(new byte[4]);
        int payloadSize = in.readInt();
        byte[] payload = new byte[payloadSize];
        in.readFully(payload);
        out.write(payload);
      }
    } catch (EOFException done) {
      LOG.log(FINE, () -> "Finished reading multiplexed stream: " + done);
    }
  }
}
