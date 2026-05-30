package com.visioncart.service.recognition;

import com.visioncart.config.VisionCartProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class RecognitionImageStorage {
    private final VisionCartProperties properties;

    public RecognitionImageStorage(VisionCartProperties properties) {
        this.properties = properties;
    }

    public String historyImageUrl(String sessionId) {
        return "/api/v1/history/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/image";
    }

    public void saveHistoryImage(String sessionId, byte[] jpegBytes) throws IOException {
        if (jpegBytes == null || jpegBytes.length == 0) {
            return;
        }
        Path root = rootDir();
        Files.createDirectories(root);
        Path target = imagePath(root, sessionId);
        Files.write(target, jpegBytes);
    }

    public Optional<Resource> loadHistoryImage(String sessionId) {
        Path root = rootDir();
        Path target = imagePath(root, sessionId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        Resource resource = new FileSystemResource(target);
        return resource.exists() && resource.isReadable() ? Optional.of(resource) : Optional.empty();
    }

    private Path rootDir() {
        String configured = properties.getRecognition().getHistoryImageDir();
        String path = configured == null || configured.isBlank() ? "data/recognition-history" : configured;
        return Path.of(path).toAbsolutePath().normalize();
    }

    private Path imagePath(Path root, String sessionId) {
        String safeName = sessionId == null ? "unknown" : sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        return root.resolve(safeName + ".jpg").normalize();
    }
}
