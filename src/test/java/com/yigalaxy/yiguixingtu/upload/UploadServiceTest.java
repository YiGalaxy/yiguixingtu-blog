package com.yigalaxy.yiguixingtu.upload;

import com.yigalaxy.yiguixingtu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void routesEachUploadTypeToItsOwnPolicy() {
        assertStored("image", "cover.png", "image/png", "cover/ba/" + HASH + ".png");
        assertStored("audio", "song.mp3", "audio/mpeg", "music/ba/" + HASH + ".mp3");
        assertStored("attachment", "notes.pdf", "application/pdf", "attachment/ba/" + HASH + ".pdf");
    }

    @Test
    void reusingIdenticalContentSkipsCapacityCheckAndWrite() {
        FileStorage storage = mock(FileStorage.class);
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.urlOf(anyString())).thenAnswer(call -> "/uploads/" + call.getArgument(0));

        UploadResult result = new UploadService(storage, new UploadProperties())
                .upload(file("folder/notes.pdf", "application/pdf"), "attachment");

        assertEquals("/uploads/attachment/ba/" + HASH + ".pdf", result.url());
        assertEquals("notes.pdf", result.name());
        verify(storage, never()).usedBytes();
        verify(storage, never()).store(any(InputStream.class), anyString(), anyString());
    }

    @Test
    void imagePolicyDoesNotAcceptAttachmentExtensions() {
        FileStorage storage = mock(FileStorage.class);
        UploadService service = new UploadService(storage, new UploadProperties());

        assertThrows(BusinessException.class, () -> service.upload(file("notes.pdf", "application/pdf"), "image"));
        verify(storage, never()).exists(anyString());
    }

    private static void assertStored(String type, String name, String contentType, String key) {
        FileStorage storage = mock(FileStorage.class);
        when(storage.store(any(InputStream.class), eq(key), eq(contentType)))
                .thenReturn("/uploads/" + key);

        UploadResult result = new UploadService(storage, new UploadProperties())
                .upload(file(name, contentType), type);

        assertEquals("/uploads/" + key, result.url());
        assertEquals(name, result.name());
        verify(storage).store(any(InputStream.class), eq(key), eq(contentType));
    }

    private static MockMultipartFile file(String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, CONTENT);
    }
}
