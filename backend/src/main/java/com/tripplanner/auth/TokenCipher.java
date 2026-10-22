package com.tripplanner.auth;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class TokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public TokenCipher() {
        this(loadKey());
    }
    TokenCipher(byte[] bytes) {
        if (bytes.length != 32) throw new IllegalStateException("카카오 토큰 암호화 키는 32바이트여야 합니다.");
        key = new SecretKeySpec(bytes.clone(), "AES");
    }
    private static byte[] loadKey() {
        try {
            String configured = System.getenv("APP_KAKAO_TOKEN_ENCRYPTION_KEY");
            if (configured != null && !configured.isBlank()) return Base64.getDecoder().decode(configured.trim());
            Path path = Path.of("data", "kakao-token.key");
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                byte[] generated = new byte[32]; new SecureRandom().nextBytes(generated);
                try { Files.writeString(path, Base64.getEncoder().encodeToString(generated), StandardOpenOption.CREATE_NEW); }
                catch (FileAlreadyExistsException ignored) { /* Another process initialized the same key. */ }
            }
            return Base64.getDecoder().decode(Files.readString(path).trim());
        } catch (Exception e) { throw new IllegalStateException("토큰 암호화 초기화 실패", e); }
    }
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] data = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(iv, iv.length + data.length);
            System.arraycopy(data, 0, result, iv.length, data.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) { throw new IllegalStateException("토큰 암호화 실패"); }
    }
    public String decrypt(String encrypted) {
        try {
            byte[] data = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("카카오에 다시 연결해 주세요."); }
    }
}
