package com.yigalaxy.yiguixingtu.upload;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentAddressedKeysTest {

    @Test
    void hashesTheStreamAndBuildsTheSharedUploadPath() throws Exception {
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        String hash = ContentAddressedKeys.sha256Hex(new ByteArrayInputStream(content));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
        assertEquals("cover/ba/" + hash + ".png", ContentAddressedKeys.objectKey("cover", hash, "png"));
        assertEquals("attachment/ba/" + hash + ".pdf",
                ContentAddressedKeys.objectKey("attachment", hash, "pdf"));
        assertEquals(hash, ContentAddressedKeys.sha256Hex(new ByteArrayInputStream(content)));
    }
}
