/*
 * SonarQube
 * Copyright (C) 2009-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.alm.client.gitlab;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.sonar.alm.client.TimeoutConfiguration;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonarqube.ws.client.OkHttpClientBuilder;

import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.nio.charset.StandardCharsets.UTF_8;

@ServerSide
public class GitlabHttpClient {

  private static final Logger LOG = Loggers.get(GitlabHttpClient.class);
  protected static final String PRIVATE_TOKEN = "Private-Token";
  protected final OkHttpClient client;

  public GitlabHttpClient(TimeoutConfiguration timeoutConfiguration) {
    client = new OkHttpClientBuilder()
      .setConnectTimeoutMs(timeoutConfiguration.getConnectTimeout())
      .setReadTimeoutMs(timeoutConfiguration.getReadTimeout())
      .build();
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, UTF_8.toString());
    } catch (UnsupportedEncodingException ex) {
      throw new IllegalStateException(ex.getCause());
    }
  }

  protected static void checkResponseIsSuccessful(Response response) throws IOException {
    checkResponseIsSuccessful(response, "GitLab Merge Request did not happen, please check your configuration");
  }

  protected static void checkResponseIsSuccessful(Response response, String errorMessage) throws IOException {
    if (!response.isSuccessful()) {
      String body = response.body().string();
      LOG.error(String.format("Gitlab API call to [%s] failed with %s http code. gitlab response content : [%s]", response.request().url().toString(), response.code(), body));
      if (isTokenRevoked(response, body)) {
        throw new IllegalArgumentException("Your GitLab token was revoked");
      } else if (isTokenExpired(response, body)) {
        throw new IllegalArgumentException("Your GitLab token is expired");
      } else if (isInsufficientScope(response, body)) {
        throw new IllegalArgumentException("Your GitLab token has insufficient scope");
      } else if (response.code() == HTTP_UNAUTHORIZED) {
        throw new IllegalArgumentException("Invalid personal access token");
      } else {
        throw new IllegalArgumentException(errorMessage);
      }
    }
  }

  private static boolean isTokenRevoked(Response response, String body) {
    if (response.code() == HTTP_UNAUTHORIZED) {
      try {
        Optional<GsonError> gitlabError = GsonError.parseOne(body);
        return gitlabError.map(GsonError::getErrorDescription).map(description -> description.contains("Token was revoked")).orElse(false);
      } catch (JsonParseException e) {
        // nothing to do
      }
    }
    return false;
  }

  private static boolean isTokenExpired(Response response, String body) {
    if (response.code() == HTTP_UNAUTHORIZED) {
      try {
        Optional<GsonError> gitlabError = GsonError.parseOne(body);
        return gitlabError.map(GsonError::getErrorDescription).map(description -> description.contains("Token is expired")).orElse(false);
      } catch (JsonParseException e) {
        // nothing to do
      }
    }
    return false;
  }

  private static boolean isInsufficientScope(Response response, String body) {
    if (response.code() == HTTP_FORBIDDEN) {
      try {
        Optional<GsonError> gitlabError = GsonError.parseOne(body);
        return gitlabError.map(GsonError::getError).map("insufficient_scope"::equals).orElse(false);
      } catch (JsonParseException e) {
        // nothing to do
      }
    }
    return false;
  }

  public Project getProject(String gitlabUrl, String pat, Long gitlabProjectId) {
    String url = String.format("%s/projects/%s", gitlabUrl, gitlabProjectId);
    LOG.debug(String.format("get project : [%s]", url));
    Request request = new Request.Builder()
      .addHeader(PRIVATE_TOKEN, pat)
      .get()
      .url(url)
      .build();

    try (Response response = client.newCall(request).execute()) {
      checkResponseIsSuccessful(response);
      String body = response.body().string();
      LOG.trace(String.format("loading project payload result : [%s]", body));
      return new GsonBuilder().create().fromJson(body, Project.class);
    } catch (JsonSyntaxException e) {
      throw new IllegalArgumentException("Could not parse GitLab answer to retrieve a project. Got a non-json payload as result.");
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  public ProjectList searchProjects(String gitlabUrl, String personalAccessToken, @Nullable String projectName,
    int pageNumber, int pageSize) {
    String url = String.format("%s/projects?archived=false&simple=true&membership=true&order_by=name&sort=asc&search=%s&page=%d&per_page=%d",
      gitlabUrl, projectName == null ? "" : urlEncode(projectName), pageNumber, pageSize);

    LOG.debug(String.format("get projects : [%s]", url));
    Request request = new Request.Builder()
      .addHeader(PRIVATE_TOKEN, personalAccessToken)
      .url(url)
      .get()
      .build();

    try (Response response = client.newCall(request).execute()) {
      checkResponseIsSuccessful(response, "Could not get projects from GitLab instance");
      List<Project> projectList = Project.parseJsonArray(response.body().string());
      int returnedPageNumber = parseAndGetIntegerHeader(response.header("X-Page"));
      int returnedPageSize = parseAndGetIntegerHeader(response.header("X-Per-Page"));
      int totalProjects = parseAndGetIntegerHeader(response.header("X-Total"));
      return new ProjectList(projectList, returnedPageNumber, returnedPageSize, totalProjects);
    } catch (JsonSyntaxException e) {
      throw new IllegalArgumentException("Could not parse GitLab answer to search projects. Got a non-json payload as result.");
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  private static int parseAndGetIntegerHeader(@Nullable String header) {
    if (header == null) {
      throw new IllegalArgumentException("Pagination data from GitLab response is missing");
    } else {
      try {
        return Integer.parseInt(header);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Could not parse pagination number", e);
      }
    }
  }

}