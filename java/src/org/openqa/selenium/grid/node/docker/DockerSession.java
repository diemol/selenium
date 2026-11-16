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

import java.net.URL;
import java.time.Instant;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.grid.node.DefaultActiveSession;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

public class DockerSession extends DefaultActiveSession {

  private final Runnable removeContainers;

  /**
   * @param removeContainers asks the session Workflow to stop the recorder, save the logs, and
   *     remove the containers
   */
  DockerSession(
      Runnable removeContainers,
      Tracer tracer,
      HttpClient client,
      SessionId id,
      URL url,
      Capabilities stereotype,
      Capabilities capabilities,
      Dialect downstream,
      Dialect upstream,
      Instant startTime) {
    super(tracer, client, id, url, downstream, upstream, stereotype, capabilities, startTime);
    this.removeContainers = Require.nonNull("Container cleanup", removeContainers);
  }

  @Override
  public boolean isRemoteFileSystem() {
    // The browser runs inside a Docker container that does not share the Node's filesystem, so
    // file upload and download commands must be forwarded to the container.
    return true;
  }

  @Override
  public void stop() {
    try {
      removeContainers.run();
    } finally {
      super.stop();
    }
  }
}
