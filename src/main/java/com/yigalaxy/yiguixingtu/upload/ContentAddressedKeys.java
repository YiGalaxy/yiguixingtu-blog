package com.yigalaxy.yiguixingtu.upload;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared content-addressing rule for uploads and legacy-file migration. */
final class ContentAddressedKeys {

    private static final int HASH_DIR_LENGTH = 2;
    private static final int HASH_BUFFER_SIZE = 8192;

    private ContentAddressedKeys() {
    }

    static String objectKey(String prefix, String hash, String extension) {
        return prefix + "/" + hash.substring(0, HASH_DIR_LENGTH) + "/" + hash + "." + extension;
    }

    static String sha256Hex(InputStream content) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境没有可用的 SHA-256 实现", e);
        }

        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        int read;
        while ((read = content.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
