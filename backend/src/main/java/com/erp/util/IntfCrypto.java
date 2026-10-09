package com.erp.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 开放接口签名与口令工具（spec open-api-gateway / api-credential-management）。
 * 抽成纯静态工具便于 JUnit 直接断言签名串与哈希口径。
 */
public final class IntfCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IntfCrypto() {
    }

    /** 签名串 = METHOD \n PATH \n HEX(SHA256(body))（design D1） */
    public static String signatureString(String method, String path, byte[] body) {
        return method + "\n" + path + "\n" + sha256Hex(body == null ? new byte[0] : body);
    }

    public static String hmacHex(String signKey, String plain) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 口令哈希 = SHA-256(salt + secret)（签发与 Basic 校验共用） */
    public static String hashSecret(String salt, String secret) {
        return sha256Hex((salt + secret).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    public static String newSalt() {
        return randomHex(8);
    }

    /** 随机明文口令（URL-safe，无易混淆字符） */
    public static String randomSecret() {
        byte[] buf = new byte[18];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String randomApiKey() {
        return "ik-" + randomHex(12);
    }

    public static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return hex(buf);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
