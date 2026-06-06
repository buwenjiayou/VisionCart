package com.visioncart.service.recognition;

import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecognitionImageStorageTest {

    @Test
    void savesAndLoadsHistoryImage() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getRecognition().setHistoryImageDir(testDir().toString());
        RecognitionImageStorage storage = new RecognitionImageStorage(properties);

        storage.saveHistoryImage("session-1", new byte[]{1, 2, 3});

        assertThat(storage.historyImageUrl("session-1")).isEqualTo("/api/v1/history/session-1/image");
        assertThat(storage.loadHistoryImage("session-1")).isPresent();
        assertThat(storage.loadHistoryImage("missing")).isEmpty();
    }

    @Test
    void savesAndLoadsCandidateCropImage() throws Exception {
        VisionCartProperties properties = new VisionCartProperties();
        properties.getRecognition().setHistoryImageDir(testDir().toString());
        RecognitionImageStorage storage = new RecognitionImageStorage(properties);

        storage.saveCandidateCrop("session-1", "candidate-1", new byte[]{4, 5, 6});

        assertThat(storage.candidateCropUrl("session-1", "candidate-1"))
                .isEqualTo("/api/v1/recognition/session-1/candidates/candidate-1/image");
        assertThat(storage.loadCandidateCrop("session-1", "candidate-1")).isPresent();
        assertThat(storage.loadCandidateCrop("session-1", "candidate-1").orElseThrow())
                .containsExactly((byte) 4, (byte) 5, (byte) 6);
        assertThat(storage.loadCandidateCropResource("session-1", "candidate-1")).isPresent();
        assertThat(storage.loadCandidateCropResource("session-1", "missing")).isEmpty();
    }

    private Path testDir() throws Exception {
        Path dir = Path.of("build", "test-data", "recognition-image-storage", UUID.randomUUID().toString());
        Files.createDirectories(dir);
        return dir;
    }
}
