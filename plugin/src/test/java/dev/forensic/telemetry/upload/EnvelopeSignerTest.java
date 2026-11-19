package dev.forensic.telemetry.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnvelopeSignerTest {

    @Test
    @DisplayName("known-answer vector matches the cloud implementation byte-for-byte")
    void knownAnswerVector() {
        // Duplicated in cloud/src/ingest/signing.test.ts. If this changes, every
        // upload 401s: update both sides together or not at all.
        byte[] body = "{\"envelope_id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(19, body.length);
        String digest = EnvelopeSigner.sha256Hex(body);
        assertEquals(
                "cca6b37a2653a4a337bc82116be13acf9c76c0c2f23563b020b4bd7b74ee5cef",
                digest);
        String canonical =
                EnvelopeSigner.canonicalString(
                        "11111111-1111-1111-1111-111111111111",
                        "k1",
                        "1700000000000",
                        "bm9uY2U=",
                        "22222222-2222-2222-2222-222222222222",
                        "1",
                        "19",
                        digest);
        String sig =
                EnvelopeSigner.hmacHex("known-answer-secret".getBytes(StandardCharsets.UTF_8),
                        canonical);
        assertEquals(
                "f5289b4e7b39e052cfe6e70c1354d151c060708385ee4d031e7aa584fa157e6f",
                sig);
    }

    @Test
    @DisplayName("canonical string is newline-joined in fixed order")
    void canonicalShape() {
        String s = EnvelopeSigner.canonicalString("a", "b", "c", "d", "e", "f", "g", "h");
        assertEquals("POST\n/v1/telemetry\na\nb\nc\nd\ne\nf\ng\nh", s);
    }

    @Test
    @DisplayName("known-answer vector for PUT matches the cloud byte-for-byte")
    void knownAnswerPutVector() {
        // Duplicated in cloud/src/ingest/signing.test.ts. Same update-both rule:
        // a PUT signed as POST must never verify server-side.
        byte[] body = "BINARY-CHUNK-BYTES-0123456789".getBytes(StandardCharsets.UTF_8);
        assertEquals(29, body.length);
        String digest = EnvelopeSigner.sha256Hex(body);
        assertEquals(
                "ad8a06d4692416853f285f2536864b83d85d3636b2eb6665464f4a253f8d3cf3",
                digest);
        String canonical =
                EnvelopeSigner.canonicalString(
                        "PUT",
                        "/v1/segments/seg/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/100-163.zst",
                        "11111111-1111-1111-1111-111111111111",
                        "k1",
                        "1700000000000",
                        "bm9uY2U=",
                        "22222222-2222-2222-2222-222222222222",
                        "1",
                        "29",
                        digest);
        String sig =
                EnvelopeSigner.hmacHex("known-answer-secret".getBytes(StandardCharsets.UTF_8),
                        canonical);
        assertEquals(
                "94280ff9c7c207d9a60338c9140015b536106dc86abe04cb8a2873eec4750e6d",
                sig);
    }

    @Test
    @DisplayName("method is upper-cased and path is signed verbatim")
    void methodAndPath() {
        String lower =
                EnvelopeSigner.canonicalString(
                        "put", "/v1/segments/seg/x", "a", "b", "c", "d", "e", "f", "g", "h");
        String upper =
                EnvelopeSigner.canonicalString(
                        "PUT", "/v1/segments/seg/x", "a", "b", "c", "d", "e", "f", "g", "h");
        assertEquals(upper, lower);
        assertTrue(lower.startsWith("PUT\n/v1/segments/seg/x\n"));
    }

    @Test
    @DisplayName("signature comparison is constant-time")
    void constantTime() {
        assertTrue(EnvelopeSigner.constantTimeEquals("abc", "abc"));
        assertFalse(EnvelopeSigner.constantTimeEquals("abc", "abd"));
        assertFalse(EnvelopeSigner.constantTimeEquals("abc", "abcd"));
    }

    @Test
    @DisplayName("different bodies give different digests")
    void digestSensitivity() {
        String d1 = EnvelopeSigner.sha256Hex(new byte[] {1, 2, 3});
        String d2 = EnvelopeSigner.sha256Hex(new byte[] {1, 2, 4});
        assertFalse(d1.equals(d2));
    }
}
