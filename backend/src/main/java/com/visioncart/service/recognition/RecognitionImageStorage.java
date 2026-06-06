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

    public String candidateCropUrl(String sessionId, String candidateId) {
        return "/api/v1/recognition/"
                + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                + "/candidates/"
                + URLEncoder.encode(candidateId, StandardCharsets.UTF_8)
                + "/image";
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

    public Optional<byte[]> loadHistoryImageBytes(String sessionId) {
        Path root = rootDir();
        Path target = imagePath(root, sessionId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void deleteHistoryImage(String sessionId) {
        try {
            Path root = rootDir();
            Path target = imagePath(root, sessionId);
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // ignore cleanup failure
        }
    }

    public void saveCandidateCrop(String sessionId, String candidateId, byte[] jpegBytes) throws IOException {
        if (jpegBytes == null || jpegBytes.length == 0) return;
        Path root = rootDir();
        Files.createDirectories(root);
        Path target = cropPath(root, sessionId, candidateId);
        Files.createDirectories(target.getParent());
        Files.write(target, jpegBytes);
    }

    public Optional<byte[]> loadCandidateCrop(String sessionId, String candidateId) {
        Path root = rootDir();
        Path target = cropPath(root, sessionId, candidateId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<Resource> loadCandidateCropResource(String sessionId, String candidateId) {
        Path root = rootDir();
        Path target = cropPath(root, sessionId, candidateId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        Resource resource = new FileSystemResource(target);
        return resource.exists() && resource.isReadable() ? Optional.of(resource) : Optional.empty();
    }

    public void deleteCandidateCrops(String sessionId) {
        try {
            Path root = rootDir();
            String safeName = sessionId == null ? "unknown" : sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
            Path cropDir = root.resolve(safeName + "_crops");
            if (Files.isDirectory(cropDir)) {
                try (var stream = Files.list(cropDir)) {
                    stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                }
                Files.deleteIfExists(cropDir);
            }
        } catch (IOException ignored) {
        }
    }

    private Path cropPath(Path root, String sessionId, String candidateId) {
        String safeSession = sessionId == null ? "unknown" : sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        String safeCandidate = candidateId == null ? "unknown" : candidateId.replaceAll("[^A-Za-z0-9_-]", "_");
        Path cropDir = root.resolve(safeSession + "_crops");
        return cropDir.resolve(safeCandidate + ".jpg").normalize();
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
