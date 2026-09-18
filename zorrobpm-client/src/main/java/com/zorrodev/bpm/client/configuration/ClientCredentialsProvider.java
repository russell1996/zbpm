package com.zorrodev.bpm.client.configuration;

/**
 * WO-API-2: injectable credential provider for the SDK clients (an interface,
 * not a hardcoded token source).
 *
 * <p>Implementations are queried on EVERY request, so rotating the credential
 * (new token string) applies to subsequent calls without rebuilding any client.
 * Returning {@code null} (or blank) sends the request without an
 * {@code Authorization} header — that is also what the default no-op provider
 * does, preserving the pre-WO-API-2 behaviour for unauthenticated servers.
 */
public interface ClientCredentialsProvider {

    /**
     * @return the current bearer token, or {@code null}/blank for anonymous calls
     */
    String currentToken();
}
