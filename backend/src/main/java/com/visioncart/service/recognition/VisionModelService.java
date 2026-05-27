package com.visioncart.service.recognition;

import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.api.dto.RecognitionCandidate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

public interface VisionModelService {

    RecognitionResult analyze(MultipartFile image, String region);

    default RecognitionResult analyze(byte[] imageBytes, String contentType, String region) {
        return analyze(new ByteArrayMultipartFile(imageBytes, contentType), region);
    }

    default boolean supportsTwoStageRecognition() {
        return false;
    }

    default List<RecognitionCandidate> detectProducts(byte[] imageBytes, String contentType, String region) {
        throw new UnsupportedOperationException("Product detection is not supported by this vision model");
    }

    default RecognitionResult extractAttributes(byte[] imageBytes,
                                                String contentType,
                                                String categoryHint,
                                                String brandHint,
                                                String region) {
        return analyze(imageBytes, contentType, region);
    }

    class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] content;
        private final String contentType;

        public ByteArrayMultipartFile(byte[] content, String contentType) {
            this.content = content;
            this.contentType = contentType;
        }

        @Override public String getName() { return "image"; }
        @Override public String getOriginalFilename() { return "image"; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
    }
}
