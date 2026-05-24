package com.visioncart.service.recognition;

import com.visioncart.api.dto.RecognitionResult;
import org.springframework.web.multipart.MultipartFile;

public interface VisionModelService {
    RecognitionResult analyze(MultipartFile image, String region);
}
