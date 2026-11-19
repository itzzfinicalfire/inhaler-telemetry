package dev.forensic.telemetry.report;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Polls the cloud's read-only shadow report and prints it to the server console.
 *
 * <p>This is the only cloud-to-server channel, and it is deliberately one-way and
 * non-authoritative: the response is plain text, every line is prefixed with
 * {@code [shadow]}, and nothing here can kick, ban, damage, teleport, or cancel an
 * event. The token is read-only ({@code REPORT_TOKEN}); it cannot export or write.
 *
 * <p>The report itself is labelled SHADOW_ONLY / UNCALIBRATED by the cloud. Printing it
 * is an observation aid for staff, not an automated verdict.
 */
public final class ConsoleReporter implements Runnable {

    private final Logger logger;
    private final String url;
    private final String token;
    private final String query;
    private final HttpClient client;
    private final Duration timeout;

    private String lastBody;
    private String lastError;

    public ConsoleReporter(
            Logger logger,
            String url,
            String token,
            String serverId,
            String sessionEpoch,
            Duration timeout) {
        this.logger = logger;
        this.url = url;
        this.token = token;
        this.query = "serverId=" + serverId + "&sessionEpoch=" + sessionEpoch;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void run() {
        try {
            String separator = url.contains("?") ? "&" : "?";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + separator + query))
                    .timeout(timeout)
                    .header("x-report-token", token)
                    .header("accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body().strip();
            if (body.isEmpty()) {
                body = "status=empty http=" + response.statusCode();
            } else if (response.statusCode() != 200) {
                body = "status=http_" + response.statusCode() + " " + body;
            }
            if (body.equals(lastBody)) {
                return;
            }
            lastBody = body;
            lastError = null;
            for (String line : body.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    logger.info("[shadow] " + trimmed);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (message.equals(lastError)) {
                return; // do not repeat an unchanged failure every interval
            }
            lastError = message;
            logger.warning("console report fetch failed: " + message);
        }
    }
}
