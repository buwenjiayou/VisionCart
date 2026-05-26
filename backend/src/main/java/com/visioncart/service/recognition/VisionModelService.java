package com.visioncart.service.recognition;

import com.visioncart.api.dto.RecognitionResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public interface VisionModelService {

    RecognitionResult analyze(MultipartFile image, String region);

    default RecognitionResult analyze(byte[] imageBytes, String contentType, String region) {
        return analyze(new ByteArrayMultipartFile(imageBytes, contentType), region);
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
