package com.david.notification_hub.discord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * Checks that a request to the interactions endpoint really came from Discord.
 *
 * Discord signs every interaction with the application's Ed25519 key, over the
 * X-Signature-Timestamp header followed by the raw body. The endpoint has to be public,
 * so this is its only authentication. Discord also probes it with a bad signature when
 * the URL is saved, and refuses an endpoint that doesn't answer that with 401.
 *
 * Java has had Ed25519 built in since 15. The one wrinkle is the key: Discord shows the
 * raw 32 bytes as hex, and Java wants them wrapped as an X.509 SubjectPublicKeyInfo,
 * which for Ed25519 is always the same 12-byte prefix.
 */
@Component
public class DiscordSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordSignatureVerifier.class);

    // SEQUENCE { SEQUENCE { OID 1.3.101.112 (Ed25519) }, BIT STRING of 32 bytes }
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    // Null when no key is configured, in which case nothing verifies
    private final PublicKey key;

    /**
     * A key that is set but malformed stops startup: failing every request instead would
     * look exactly like Discord sending bad signatures.
     */
    public DiscordSignatureVerifier(@Value("${discord.publicKey:}") String publicKeyHex) {
        String hex = publicKeyHex == null ? "" : publicKeyHex.strip();
        if (hex.isEmpty()) {
            this.key = null;
            log.info("Discord interactions disabled (set DISCORD_PUBLIC_KEY to enable).");
        } else {
            this.key = parse(hex);
        }
    }

    /** @return true only for a well-formed signature that matches. Never throws. */
    public boolean verify(String signatureHex, String timestamp, byte[] body) {
        if (key == null || signatureHex == null || timestamp == null || body == null) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(timestamp.getBytes(StandardCharsets.UTF_8));
            verifier.update(body);
            return verifier.verify(HexFormat.of().parseHex(signatureHex));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            // Not hex, wrong length, not a valid signature encoding: all just "no"
            return false;
        }
    }

    static PublicKey parse(String hex) {
        byte[] raw;
        try {
            raw = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DISCORD_PUBLIC_KEY is not hex", e);
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException(
                    "DISCORD_PUBLIC_KEY must be 64 hex characters (32 bytes), got " + raw.length + " bytes");
        }

        byte[] der = new byte[X509_PREFIX.length + raw.length];
        System.arraycopy(X509_PREFIX, 0, der, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, der, X509_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("DISCORD_PUBLIC_KEY is not a valid Ed25519 key", e);
        }
    }
}
