package com.erp.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加解密工具（add-data-extract-console design D1/D2）：
 * 数据源连接信息加密存储（FR-4.10-2-1），明文不出接口。
 * 密钥来源 app.bi.conn-secret（默认占位值——生产须配置真实密钥）。
 */
public final class AesUtil {

    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesUtil() {
    }

    private static SecretKeySpec key(String secret) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(d, "AES");
    }

    /** 加密：Base64(iv(12) + ciphertext) */
    public static String encrypt(String plain, String secret) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new ServiceException(500, "连接信息加密失败：" + e.getMessage());
        }
    }

    public static String decrypt(String b64, String secret) {
        try {
            byte[] in = Base64.getDecoder().decode(b64);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(secret),
                    new GCMParameterSpec(GCM_TAG_BITS, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServiceException(500, "连接信息解密失败：" + e.getMessage());
        }
    }

    /** 详情脱敏展示（掩码尾 4 位） */
    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        return "****" + plain.substring(Math.max(0, plain.length() - 4));
    }

    // ---- 文件级（add-export-console design D4：导出文件 AES-256 加密落盘，明文不出服务端）----

    /**
     * 加密字节流到文件：格式 iv(12) + ciphertext+tag，与 {@link #encrypt} 同族（GCM）。
     * 沿用项目既有 AES-GCM 方案（优于 design 草案的 CBC——认证加密防篡改，且与 14.2 连接加密同族）。
     */
    public static void encryptToFile(byte[] plain, java.nio.file.Path target, String secret) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            java.nio.file.Files.write(target, out);
        } catch (Exception e) {
            throw new ServiceException(500, "导出文件加密失败：" + e.getMessage());
        }
    }

    /** 解密文件为字节流（下载流出用）；格式 iv(12) + ciphertext+tag */
    public static byte[] decryptFile(java.nio.file.Path source, String secret) {
        try {
            byte[] in = java.nio.file.Files.readAllBytes(source);
            if (in.length < 13) {
                throw new ServiceException(500, "密文文件损坏（长度不足）");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(secret),
                    new GCMParameterSpec(GCM_TAG_BITS, in, 0, 12));
            return c.doFinal(in, 12, in.length - 12);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "导出文件解密失败：" + e.getMessage());
        }
    }
}
