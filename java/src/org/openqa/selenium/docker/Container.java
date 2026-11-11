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

package org.openqa.selenium.docker;

import java.time.Duration;
import java.util.logging.Logger;
import org.openqa.selenium.internal.Require;

/**
 * A handle to a Docker container. It keeps no state of its own: every call asks Docker, so a handle
 * made from an id, for example after a restart, works the same as the one {@link Docker#create}
 * returns. Errors are thrown, so callers can retry.
 */
public class Container {

  private static final Logger LOG = Logger.getLogger(Container.class.getName());
  private final DockerProtocol protocol;
  private final ContainerId id;

  public Container(DockerProtocol protocol, ContainerId id) {
    this.protocol = Require.nonNull("Protocol", protocol);
    this.id = Require.nonNull("Container id", id);
  }

  public ContainerId getId() {
    return id;
  }

  /** Starts the container. Starting a running container is not an error. */
  public void start() {
    LOG.info("Starting container " + id);
    protocol.startContainer(id);
  }

  /**
   * Stops the container. Stopping a stopped container is not an error.
   *
   * @param timeout how long Docker waits before it kills the container
   */
  public void stop(Duration timeout) {
    Require.nonNull("Timeout to wait for", timeout);
    LOG.info("Stopping container " + id);
    protocol.stopContainer(id, timeout);
  }

  /** Removes the container, stopping it first if needed. Removing a missing one is not an error. */
  public void remove() {
    LOG.info("Removing container " + id);
    protocol.removeContainer(id);
  }

  public ContainerInfo inspect() {
    LOG.info("Inspecting container " + id);
    return protocol.inspectContainer(id);
  }

  /**
   * Returns the stdout and stderr of the container, in Docker's multiplexed stream format.
   *
   * @return the container logs
   */
  public ContainerLogs getLogs() {
    LOG.info("Getting logs " + id);
    return protocol.getContainerLogs(id);
  }
}
