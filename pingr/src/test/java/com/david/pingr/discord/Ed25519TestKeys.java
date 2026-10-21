package com.david.pingr.discord;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;

/** Plays Discord's side of the signature: a key pair, and signing as Discord does. */
final class Ed25519TestKeys {

    private Ed25519TestKeys() {}

    static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public key as Discord's developer portal shows it: 32 raw bytes in hex. */
    static String rawHex(PublicKey key) {
        byte[] x509 = key.getEncoded(); // a fixed 12-byte prefix, then the raw key
        return HexFormat.of().formatHex(Arrays.copyOfRange(x509, x509.length - 32, x509.length));
    }

    /** The X-Signature-Ed25519 value for this timestamp and body. */
    static String sign(KeyPair keys, String timestamp, String body) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(keys.getPrivate());
            s.update(timestamp.getBytes(StandardCharsets.UTF_8));
            s.update(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
