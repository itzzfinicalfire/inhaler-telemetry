package dev.forensic.telemetry.upload;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope request signer: HMAC-SHA256 over the canonical signing string.
 *
 * <p>The canonical form is byte-identical to the cloud's
 * ({@code cloud/src/ingest/signing.ts}): fixed field order, newline-joined, method upper-cased,
 * hex digests lowercase. Any drift here produces 401s on every upload, which is why the
 * known-answer vector in {@code EnvelopeSignerTest} pins the exact bytes and is duplicated in
 * the cloud's {@code signing.test.ts}. Update both or neither.
 *
 * <p>Threading: a new {@link Mac} per call (they are not thread-safe and pooling them saves
 * nothing at flush cadence). No caching of secrets: the key lives in config memory only.
 */
public final class EnvelopeSigner {

    public static final String PATH = "/v1/telemetry";

    private EnvelopeSigner() {}

    public static String canonicalString(
            String serverId,
            String keyId,
            String timestampMs,
            String nonceBase64,
            String sessionEpoch,
            String schemaVersion,
            String contentLength,
            String bodySha256Hex) {
        return canonicalString(
                "POST",
                PATH,
                serverId,
                keyId,
                timestampMs,
                nonceBase64,
                sessionEpoch,
                schemaVersion,
                contentLength,
                bodySha256Hex);
    }

    /**
     * Parameterized method/path form for segment PUTs. The method and path are
     * part of the signed string precisely so a captured envelope POST cannot be
     * replayed as a segment PUT or vice versa. Callers must pass the actual
     * request line values, never constants copied from another endpoint.
     */
    public static String canonicalString(
            String method,
            String path,
            String serverId,
            String keyId,
            String timestampMs,
            String nonceBase64,
            String sessionEpoch,
            String schemaVersion,
            String contentLength,
            String bodySha256Hex) {
        return method.toUpperCase(java.util.Locale.ROOT) + "\n"
                + path + "\n"
                + serverId + "\n"
                + keyId + "\n"
                + timestampMs + "\n"
                + nonceBase64 + "\n"
                + sessionEpoch + "\n"
                + schemaVersion + "\n"
                + contentLength + "\n"
                + bodySha256Hex;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hmacHex(byte[] secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time comparison: signatures compare in full, always. */
    public static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
