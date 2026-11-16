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

import static org.openqa.selenium.remote.Dialect.W3C;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.RetrySessionRequestException;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.docker.Image;
import org.openqa.selenium.grid.data.CreateSessionRequest;
import org.openqa.selenium.grid.node.ActiveSession;
import org.openqa.selenium.grid.node.SessionFactory;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.AttributeKey;
import org.openqa.selenium.remote.tracing.AttributeMap;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Status;
import org.openqa.selenium.remote.tracing.Tracer;

/**
 * Creates Docker sessions through {@link DockerSessionWorkflow}. The factory only prepares the
 * input and turns the Workflow's answer into an {@link ActiveSession}. Creating, recording, and
 * removing the containers is the Workflow's job, so it survives failures and Node restarts.
 */
public class DockerSessionFactory implements SessionFactory {

  private static final Logger LOG = Logger.getLogger(DockerSessionFactory.class.getName());

  private final Tracer tracer;
  private final HttpClient.Factory clientFactory;
  private final Duration sessionTimeout;
  private final Duration serverStartTimeout;
  private final DockerSessionRunner runner;
  private final DockerContainers containers;
  private final URI dockerUri;
  private final Image browserImage;
  private final Capabilities stereotype;
  private final Predicate<Capabilities> predicate;
  private final Duration stopGracePeriod;

  public DockerSessionFactory(
      Tracer tracer,
      HttpClient.Factory clientFactory,
      Duration sessionTimeout,
      Duration serverStartTimeout,
      DockerSessionRunner runner,
      DockerContainers containers,
      URI dockerUri,
      Image browserImage,
      Capabilities stereotype,
      Predicate<Capabilities> predicate,
      Duration stopGracePeriod) {
    this.tracer = Require.nonNull("Tracer", tracer);
    this.clientFactory = Require.nonNull("HTTP client", clientFactory);
    this.sessionTimeout = Require.nonNull("Session timeout", sessionTimeout);
    this.serverStartTimeout = Require.nonNull("Server start timeout", serverStartTimeout);
    this.runner = Require.nonNull("Docker session runner", runner);
    this.containers = Require.nonNull("Docker containers", containers);
    this.dockerUri = Require.nonNull("Docker URI", dockerUri);
    this.browserImage = Require.nonNull("Docker browser image", browserImage);
    this.stereotype = ImmutableCapabilities.copyOf(Require.nonNull("Stereotype", stereotype));
    this.predicate = Require.nonNull("Accepted capabilities predicate", predicate);
    this.stopGracePeriod = Require.nonNull("Container stop grace period", stopGracePeriod);
  }

  @Override
  public Capabilities getStereotype() {
    return stereotype;
  }

  @Override
  public boolean test(Capabilities capabilities) {
    return predicate.test(capabilities);
  }

  @Override
  public Either<WebDriverException, ActiveSession> apply(CreateSessionRequest sessionRequest) {
    LOG.info("Starting session for " + sessionRequest.getDesiredCapabilities());
    DockerSessionSpec spec = createSpec(sessionRequest.getDesiredCapabilities());

    try (Span span = tracer.getCurrentContext().createSpan("docker_session_factory.apply")) {
      AttributeMap attributeMap = tracer.createAttributeMap();
      attributeMap.put(AttributeKey.LOGGER_CLASS.getKey(), this.getClass().getName());
      attributeMap.put("docker.browser.image", browserImage.toString());
      attributeMap.put(
          "docker.session.workflow", DockerSessionSpec.workflowId(spec.sessionIdentifier));

      DockerSessionStartResult startResult;
      try {
        startResult = runner.start(spec);
      } catch (RuntimeException e) {
        // Temporal could not run the Workflow. Stop it in case it started, so it does not create
        // a browser that nobody uses.
        runner.stop(spec.sessionIdentifier, Duration.ZERO);
        return failed(
            span,
            attributeMap,
            new RetrySessionRequestException(
                "Unable to run the Docker session workflow: " + e.getMessage(), e));
      }

      switch (startResult.outcome) {
        case RETRY:
          return failed(span, attributeMap, new RetrySessionRequestException(startResult.message));
        case NOT_CREATED:
          return failed(span, attributeMap, new SessionNotCreatedException(startResult.message));
        case CREATED:
        default:
          break;
      }

      SessionId id = new SessionId(startResult.sessionId);
      URL url = toUrl(startResult.url);
      HttpClient client =
          clientFactory.createClient(
              DockerSessionActivitiesImpl.withBasicAuth(
                  ClientConfig.defaultConfig().baseUrl(url).readTimeout(sessionTimeout)));
      Dialect upstream = Dialect.valueOf(startResult.dialect);
      Dialect downstream =
          sessionRequest.getDownstreamDialects().contains(upstream) ? upstream : W3C;

      attributeMap.put("docker.server.url", startResult.url);
      attributeMap.put(AttributeKey.DOWNSTREAM_DIALECT.getKey(), downstream.toString());
      span.addEvent("Docker session workflow created session", attributeMap);
      LOG.fine(String.format("Created session: %s - %s", id, startResult.capabilities));

      Duration waitForCleanup = stopGracePeriod.multipliedBy(2).plusSeconds(30);
      return Either.right(
          new DockerSession(
              () -> runner.stop(spec.sessionIdentifier, waitForCleanup),
              tracer,
              client,
              id,
              url,
              stereotype,
              new ImmutableCapabilities(startResult.capabilities),
              downstream,
              upstream,
              Instant.now()));
    }
  }

  private static Either<WebDriverException, ActiveSession> failed(
      Span span, AttributeMap attributeMap, WebDriverException e) {
    span.setAttribute(AttributeKey.ERROR.getKey(), true);
    span.setStatus(Status.CANCELLED);
    attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), e.getMessage());
    span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);
    LOG.log(Level.WARNING, e.getMessage());
    return Either.left(e);
  }

  DockerSessionSpec createSpec(Capabilities desired) {
    // Unique per request, so concurrent requests never share container names or Workflow ids.
    String browserName = desired.getBrowserName();
    browserName = browserName.isEmpty() ? "unknown" : browserName.toLowerCase();
    String sessionIdentifier =
        String.format(
            "%s-%d-%s",
            browserName, System.currentTimeMillis(), UUID.randomUUID().toString().substring(0, 8));
    boolean runningInDocker = containers.runningInDocker();
    int port = runningInDocker ? DockerContainers.BROWSER_PORT : PortProber.findFreePort();

    DockerSessionSpec spec = new DockerSessionSpec();
    spec.sessionIdentifier = sessionIdentifier;
    spec.desiredCapabilities = new HashMap<>(desired.asMap());
    spec.browserImage = browserImage.getName();
    spec.browserPort = port;
    spec.browserHost = runningInDocker ? null : browserHost();
    spec.serverStartTimeoutSeconds = serverStartTimeout.toSeconds();
    spec.sessionTimeoutSeconds = sessionTimeout.toSeconds();
    spec.stopGracePeriodSeconds = stopGracePeriod.toSeconds();
    spec.saveAssets = containers.savesAssets();
    spec.recordVideo = containers.usesRecorder(desired);
    return spec;
  }

  private String browserHost() {
    String scheme = dockerUri.getScheme();
    if (scheme != null && (scheme.startsWith("tcp") || scheme.startsWith("http"))) {
      return dockerUri.getHost();
    }
    return "localhost";
  }

  private static URL toUrl(String url) {
    try {
      return new URL(url);
    } catch (MalformedURLException e) {
      throw new SessionNotCreatedException(e.getMessage(), e);
    }
  }
}
