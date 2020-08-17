/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.flight.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightServerMiddleware;

import io.grpc.Context;

/**
 * Middleware that's used to validate credentials during the handshake and verify
 * the bearer token in subsequent requests.
 */
public class ServerAuthMiddleware implements FlightServerMiddleware {
  /**
   * Factory for accessing ServerAuthMiddleware.
   */
  public static class Factory implements FlightServerMiddleware.Factory<ServerAuthMiddleware> {
    private final ServerAuthHandler authHandler;
    private final GeneratedBearerTokenAuthHandler bearerTokenAuthHandler = new GeneratedBearerTokenAuthHandler();
    private final Map<String, String> tokenToIdentityMap = new ConcurrentHashMap<>();

    public Factory(ServerAuthHandler authHandler) {
      this.authHandler = authHandler;
    }

    public String getPeerForBearer(String bearerToken) {
      return bearerTokenAuthHandler.getPeerForBearerToken(bearerToken);
    }

    @Override
    public ServerAuthMiddleware onCallStarted(CallInfo callInfo, CallHeaders incomingHeaders) {
      if (callInfo.method().equals(AuthConstants.HANDSHAKE_DESCRIPTOR_NAME)) {
        final ServerAuthHandler.HandshakeResult result = authHandler.authenticate(incomingHeaders);
        final String bearerToken = bearerTokenAuthHandler.registerBearer(result);
        return new ServerAuthMiddleware(result.getPeerIdentity(), bearerToken);
      }

      final String bearerToken = AuthUtilities.getValueFromAuthHeader(incomingHeaders, AuthConstants.BEARER_PREFIX);
      // No bearer token provided. Auth handler may explicitly allow this.
      if (bearerToken == null) {
        if (authHandler.validateBearer(null)) {
          return new ServerAuthMiddleware("", null);
        }
        throw new FlightRuntimeException(CallStatus.UNAUTHENTICATED);
      }

      if (!authHandler.validateBearer(bearerToken) && !bearerTokenAuthHandler.validateBearer(bearerToken)) {
        throw new FlightRuntimeException(CallStatus.UNAUTHORIZED);
      }

      final String peerIdentity = bearerTokenAuthHandler.getPeerForBearerToken(bearerToken);
      return new ServerAuthMiddleware(peerIdentity, null);
    }
  }

  private final String bearerToken;
  private final String peerIdentity;

  public ServerAuthMiddleware(String peerIdentity, String bearerToken) {
    this.peerIdentity = peerIdentity;
    this.bearerToken = bearerToken;
  }

  @Override
  public Context onAuthenticationSuccess(Context currentContext) {
    return currentContext.withValue(AuthConstants.PEER_IDENTITY_KEY, peerIdentity);
  }

  @Override
  public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {
    if (bearerToken != null) {
      outgoingHeaders.insert(AuthConstants.AUTHORIZATION_HEADER, AuthConstants.BEARER_PREFIX + bearerToken);
    }
  }

  @Override
  public void onCallCompleted(CallStatus status) {

  }

  @Override
  public void onCallErrored(Throwable err) {

  }
}
