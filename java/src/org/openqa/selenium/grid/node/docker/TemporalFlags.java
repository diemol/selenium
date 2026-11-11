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

import static org.openqa.selenium.grid.config.StandardGridRoles.NODE_ROLE;

import com.beust.jcommander.Parameter;
import com.google.auto.service.AutoService;
import java.util.Collections;
import java.util.Set;
import org.openqa.selenium.grid.config.ConfigValue;
import org.openqa.selenium.grid.config.HasRoles;
import org.openqa.selenium.grid.config.Role;

@SuppressWarnings("FieldMayBeFinal")
@AutoService(HasRoles.class)
public class TemporalFlags implements HasRoles {

  @Parameter(
      names = {"--temporal-target"},
      description =
          "Address of the Temporal Service that runs the Docker session Workflows. For Temporal"
              + " Cloud, use the endpoint from the Cloud UI and set the TEMPORAL_API_KEY"
              + " environment variable.")
  @ConfigValue(
      section = TemporalOptions.TEMPORAL_SECTION,
      name = "target",
      example = "\"" + TemporalOptions.DEFAULT_TARGET + "\"")
  private String target;

  @Parameter(
      names = {"--temporal-namespace"},
      description = "Temporal namespace for the Docker session Workflows")
  @ConfigValue(
      section = TemporalOptions.TEMPORAL_SECTION,
      name = "namespace",
      example = "\"" + TemporalOptions.DEFAULT_NAMESPACE + "\"")
  private String namespace;

  @Parameter(
      names = {"--temporal-task-queue"},
      description =
          "Task queue of this Node. Must be unique per Node and stay the same across restarts."
              + " Defaults to selenium-docker-node-<hostname>.")
  @ConfigValue(
      section = TemporalOptions.TEMPORAL_SECTION,
      name = "task-queue",
      example = "\"selenium-docker-node-1\"")
  private String taskQueue;

  @Override
  public Set<Role> getRoles() {
    return Collections.singleton(NODE_ROLE);
  }
}
