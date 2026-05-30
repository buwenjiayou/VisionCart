package com.visioncart.service.recognition;

import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionImageStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsHistoryImage() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getRecognition().setHistoryImageDir(tempDir.toString());
        RecognitionImageStorage storage = new RecognitionImageStorage(properties);

        storage.saveHistoryImage("session-1", new byte[]{1, 2, 3});

        assertThat(storage.historyImageUrl("session-1")).isEqualTo("/api/v1/history/session-1/image");
        assertThat(storage.loadHistoryImage("session-1")).isPresent();
        assertThat(storage.loadHistoryImage("missing")).isEmpty();
    }
}
