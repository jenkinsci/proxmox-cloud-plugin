package org.jenkinsci.plugins.proxmox;

import com.trilead.ssh2.crypto.PEMDecoder;
import com.trilead.ssh2.signature.KeyAlgorithm;
import com.trilead.ssh2.signature.KeyAlgorithmManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

public class SshKeyUtil {

    private SshKeyUtil() {}

    @SuppressWarnings("unchecked")
    public static String deriveOpenSshPublicKey(String pemPrivateKey, String passphrase) throws IOException {
        KeyPair keyPair = PEMDecoder.decodeKeyPair(pemPrivateKey.toCharArray(), passphrase);
        PublicKey publicKey = keyPair.getPublic();

        for (KeyAlgorithm<PublicKey, PrivateKey> algo : KeyAlgorithmManager.getSupportedAlgorithms()) {
            if (algo.supportsKey(keyPair.getPrivate())) {
                byte[] encoded = algo.encodePublicKey(publicKey);
                String keyType = readKeyType(encoded);
                return keyType + " " + Base64.getEncoder().encodeToString(encoded);
            }
        }

        throw new IOException("Unsupported SSH key type: " + publicKey.getAlgorithm());
    }

    private static String readKeyType(byte[] wireFormat) {
        int len = ((wireFormat[0] & 0xFF) << 24) | ((wireFormat[1] & 0xFF) << 16)
                | ((wireFormat[2] & 0xFF) << 8) | (wireFormat[3] & 0xFF);
        return new String(wireFormat, 4, len, StandardCharsets.US_ASCII);
    }
}
