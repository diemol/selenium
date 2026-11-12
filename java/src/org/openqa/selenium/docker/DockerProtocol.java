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

public interface DockerProtocol {
  String version();

  Image getImage(String imageName) throws DockerException;

  Container create(ContainerConfig info);

  void startContainer(ContainerId id) throws DockerException;

  boolean isContainerPresent(ContainerId id) throws DockerException;

  void stopContainer(ContainerId id, Duration timeout) throws DockerException;

  /**
   * Removes a container, stopping it first if it is running. Removing a container that does not
   * exist is not an error.
   *
   * @param id the container id or name
   * @throws DockerException when the daemon refuses to remove the container
   */
  default void removeContainer(ContainerId id) throws DockerException {
    throw new UnsupportedOperationException("Removing containers is not supported");
  }

  ContainerInfo inspectContainer(ContainerId id) throws DockerException;

  ContainerLogs getContainerLogs(ContainerId id) throws DockerException;
}
