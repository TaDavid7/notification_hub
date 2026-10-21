package com.david.notification_hub.discord;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordSignatureVerifierTest {

    // RFC 8032 section 7.1, TEST 1: a known key and its signature of the empty message
    private static final String RFC_PUBLIC_KEY =
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String RFC_SIGNATURE =
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

    private static final KeyPair KEYS = Ed25519TestKeys.generate();
    private static final DiscordSignatureVerifier VERIFIER =
            new DiscordSignatureVerifier(Ed25519TestKeys.rawHex(KEYS.getPublic()));

    private static final String TS = "1760000000";
    private static final String BODY = "{\"type\":1}";

    @Test
    void acceptsTheRfc8032TestVector() {
        // What gets signed is timestamp + body, so with both empty it is the RFC's empty
        // message. This checks the raw-key wrapping against an outside source rather than
        // against keys this code made itself.
        DiscordSignatureVerifier rfc = new DiscordSignatureVerifier(RFC_PUBLIC_KEY);

        assertTrue(rfc.verify(RFC_SIGNATURE, "", new byte[0]));
    }

    @Test
    void acceptsWhatTheMatchingKeySigned() {
        assertTrue(VERIFIER.verify(Ed25519TestKeys.sign(KEYS, TS, BODY), TS, bytes(BODY)));
    }

    @Test
    void rejectsAnAlteredBody() {
        String signature = Ed25519TestKeys.sign(KEYS, TS, BODY);

        assertFalse(VERIFIER.verify(signature, TS, bytes("{\"type\":2}")));
    }

    @Test
    void rejectsAnAlteredTimestamp() {
        // The timestamp is signed too, so an old request can't be replayed under a new one
        String signature = Ed25519TestKeys.sign(KEYS, TS, BODY);

        assertFalse(VERIFIER.verify(signature, "1760000001", bytes(BODY)));
    }

    @Test
    void rejectsAnotherKeysSignature() {
        String signature = Ed25519TestKeys.sign(Ed25519TestKeys.generate(), TS, BODY);

        assertFalse(VERIFIER.verify(signature, TS, bytes(BODY)));
    }

    @Test
    void rejectsGarbageWithoutThrowing() {
        assertFalse(VERIFIER.verify("not hex", TS, bytes(BODY)));
        assertFalse(VERIFIER.verify("abcd", TS, bytes(BODY)));
        assertFalse(VERIFIER.verify(null, TS, bytes(BODY)));
        assertFalse(VERIFIER.verify(Ed25519TestKeys.sign(KEYS, TS, BODY), null, bytes(BODY)));
        assertFalse(VERIFIER.verify(Ed25519TestKeys.sign(KEYS, TS, BODY), TS, null));
    }

    @Test
    void withNoKeyConfiguredNothingVerifies() {
        DiscordSignatureVerifier unconfigured = new DiscordSignatureVerifier("");

        assertFalse(unconfigured.verify(Ed25519TestKeys.sign(KEYS, TS, BODY), TS, bytes(BODY)));
    }

    @Test
    void aMalformedKeyStopsStartup() {
        assertThrows(IllegalArgumentException.class, () -> new DiscordSignatureVerifier("not-hex"));
        assertThrows(IllegalArgumentException.class, () -> new DiscordSignatureVerifier("abcd"));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscordSignatureVerifier(RFC_PUBLIC_KEY.substring(2)), "31 bytes");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
