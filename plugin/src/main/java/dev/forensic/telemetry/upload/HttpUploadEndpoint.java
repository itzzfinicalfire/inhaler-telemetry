package dev.forensic.telemetry.upload;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * JDK HttpClient endpoint. One shared client: connection pooling and HTTP/1.1 keep-alive across
 * envelopes come free, and a new client per upload would defeat both while allocating.
 */
public final class HttpUploadEndpoint implements UploadEndpoint {

    private final HttpClient client;
    private final URI uri;

    public HttpUploadEndpoint(String endpointUrl, Duration timeout) {
        this.client =
                HttpClient.newBuilder().connectTimeout(timeout).followRedirects(
                        HttpClient.Redirect.NEVER).build();
        this.uri = URI.create(endpointUrl);
    }

    @Override
    public CompletableFuture<Result> post(Request request) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).POST(
                        HttpRequest.BodyPublishers.ofByteArray(request.body()));
        return send(builder, request);
    }

    @Override
    public CompletableFuture<Result> put(String path, Request request) {
        // The base URI from config points at POST /v1/telemetry; the segment path
        // replaces it wholesale. Resolving against the base would silently merge
        // paths if the base ever gained a trailing slash, so the full URI is
        // rebuilt from scheme + authority instead.
        String base = uri.getScheme() + "://" + uri.getAuthority();
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(30))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        return send(builder, request);
    }

    private CompletableFuture<Result> send(HttpRequest.Builder builder, Request request) {
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        // No redirects, ever: a 3xx from the ingest endpoint is a misconfiguration or an
        // interception, and silently following it would send signed telemetry elsewhere.
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new Result(response.statusCode(), response.body()));
    }
}
