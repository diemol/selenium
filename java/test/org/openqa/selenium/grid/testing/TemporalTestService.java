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

package org.openqa.selenium.grid.testing;

import io.temporal.testserver.TestServer;
import java.io.Closeable;
import org.openqa.selenium.net.PortProber;

/**
 * A Temporal Service for tests that start a hub, a Distributor, or a Node server, because Nodes
 * register through Temporal. It runs in the test's JVM on a free port, so servers in other
 * processes reach it too. Time runs normally.
 */
public final class TemporalTestService implements Closeable {

  private final TestServer.PortBoundTestServer server;
  private final String target;

  public TemporalTestService() {
    int port = PortProber.findFreePort();
    this.server = TestServer.createPortBoundServer(port, true);
    this.target = "127.0.0.1:" + port;
  }

  /** The address to pass as {@code --temporal-target}. */
  public String getTarget() {
    return target;
  }

  /** A {@code [temporal]} section for a TOML config. */
  public String toml() {
    return "[temporal]\ntarget = \"" + target + "\"";
  }

  @Override
  public void close() {
    server.close();
  }
}
