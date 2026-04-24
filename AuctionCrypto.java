import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class AuctionCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private AuctionCrypto() {
    }

    public static String generateNonce() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return toBase64(nonce);
    }

    public static SecretKey deriveLoginKey(String password, String nonceBase64) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    fromBase64(nonceBase64),
                    PBKDF2_ITERATIONS,
                    KEY_LENGTH_BITS
            );
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nao foi possivel derivar a chave de login.", e);
        }
    }

    public static SecretKey deriveSessionKey(SecretKey loginKey) {
        return new SecretKeySpec(hmac(loginKey.getEncoded(), "SESSION"), "AES");
    }

    public static String createProof(String username, SecretKey loginKey) {
        return toBase64(hmac(loginKey.getEncoded(), "AUTH|" + username));
    }

    public static boolean proofMatches(String expectedProofBase64, String actualProofBase64) {
        try {
            return MessageDigest.isEqual(fromBase64(expectedProofBase64), fromBase64(actualProofBase64));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String encryptMessage(SecretKey sessionKey, String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return "SECURE|" + toBase64(iv) + "|" + toBase64(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nao foi possivel criptografar a mensagem.", e);
        }
    }

    public static String decryptMessage(String encryptedMessage, SecretKey sessionKey) {
        String[] parts = encryptedMessage.split("\\|", 3);
        if (parts.length < 3 || !"SECURE".equals(parts[0])) {
            throw new IllegalStateException("Envelope criptografado invalido.");
        }

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    sessionKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, fromBase64(parts[1]))
            );
            byte[] plainBytes = cipher.doFinal(fromBase64(parts[2]));
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nao foi possivel descriptografar a mensagem.", e);
        }
    }

    public static boolean isSecureEnvelope(String message) {
        return message != null && message.startsWith("SECURE|");
    }

    private static byte[] hmac(byte[] keyBytes, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nao foi possivel calcular o HMAC.", e);
        }
    }

    private static String toBase64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] fromBase64(String value) {
        return Base64.getDecoder().decode(value);
    }
}
