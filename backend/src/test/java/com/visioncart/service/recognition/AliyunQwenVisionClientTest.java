package com.visioncart.service.recognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.RecognitionCandidate;
import com.visioncart.api.dto.RecognitionResult;
import com.visioncart.config.VisionCartProperties;
import com.visioncart.service.ai.AiTraceService;
import com.visioncart.service.ai.PromptLoader;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AliyunQwenVisionClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesFlashDetectionJsonAndNormalizesBbox() throws Exception {
        AliyunQwenVisionClient client = client();
        String content = """
                {
                  "products": [
                    {"bbox": [0.1, 0.2, 0.9, 0.8], "category": "无线鼠标", "brand": null, "confidence": 0.91}
                  ],
                  "total": 1
                }
                """;

        List<RecognitionCandidate> candidates = client.parseDetectionResponse(openAiBody(content), imageBytes(100, 200));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).bbox()).containsExactly(10, 40, 90, 160);
        assertThat(candidates.get(0).category()).isEqualTo("无线鼠标");
        assertThat(candidates.get(0).brand()).isNull();
    }

    @Test
    void parsesFlashDetectionJsonInsideMarkdownFence() throws Exception {
        AliyunQwenVisionClient client = client();
        String content = """
                ```json
                {
                  "products": [
                    {"bbox": [10, 20, 90, 160], "category": "无线鼠标", "brand": "未知", "confidence": 1.2}
                  ],
                  "total": 1
                }
                ```
                """;

        List<RecognitionCandidate> candidates = client.parseDetectionResponse(openAiBody(content), imageBytes(100, 200));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).brand()).isNull();
        assertThat(candidates.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void parsesFlashDetectionBboxInNormalizedThousandSpace() throws Exception {
        AliyunQwenVisionClient client = client();
        String content = """
                {
                  "products": [
                    {"bbox": [100, 200, 900, 800], "category": "鏃犵嚎榧犳爣", "brand": null, "confidence": 0.91}
                  ],
                  "total": 1
                }
                """;

        List<RecognitionCandidate> candidates = client.parseDetectionResponse(openAiBody(content), imageBytes(1000, 500));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).bbox()).containsExactly(100, 100, 900, 400);
    }

    @Test
    void parsesThousandSpaceBboxOnNonThousandImageWithoutExpandingToFullImage() throws Exception {
        AliyunQwenVisionClient client = client();
        String content = """
                {
                  "products": [
                    {"bbox": [100, 200, 900, 800], "category": "camera", "brand": null, "confidence": 0.91}
                  ],
                  "total": 1
                }
                """;

        List<RecognitionCandidate> candidates = client.parseDetectionResponse(openAiBody(content), imageBytes(800, 600));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).bbox()).containsExactly(80, 120, 720, 480);
    }

    @Test
    void ignoresInvalidFlashBbox() throws Exception {
        AliyunQwenVisionClient client = client();
        String content = """
                {
                  "products": [
                    {"bbox": ["x", 20, 90, 160], "category": "无线鼠标", "brand": null, "confidence": 0.91}
                  ],
                  "total": 1
                }
                """;

        List<RecognitionCandidate> candidates = client.parseDetectionResponse(openAiBody(content), imageBytes(100, 200));

        assertThat(candidates).isEmpty();
    }

    @Test
    void parsesPlusResultIntoRecognitionContract() throws Exception {
        AliyunQwenVisionClient client = client();
        String content = """
                {
                  "category": {"level1": "数码", "level2": "电脑外设", "level3": "无线鼠标", "confidence": 0.92},
                  "brand": "罗技",
                  "attributes": {
                    "颜色": {"value": "白色", "confidence": 0.88, "verified": false}
                  },
                  "keywords": ["罗技无线鼠标", "白色鼠标"],
                  "overall_confidence": 0.9
                }
                """;

        RecognitionResult result = client.parseRecognitionResponse(openAiBody(content), "鼠标", "罗技");

        assertThat(result.category().level3()).isEqualTo("无线鼠标");
        assertThat(result.attributes().get("品牌").value()).isEqualTo("罗技");
        assertThat(result.attributes().get("颜色").value()).isEqualTo("白色");
        assertThat(result.keywords()).contains("罗技无线鼠标");
    }

    @Test
    void cropsBboxWithinImageBounds() throws Exception {
        ImageProcessor processor = new ImageProcessor(new VisionCartProperties());

        ImageProcessor.CroppedImage crop = processor.cropToJpeg(imageBytes(100, 80), List.of(-20, 10, 80, 70));

        assertThat(crop.bytes()).isNotEmpty();
        assertThat(crop.width()).isGreaterThan(70);
        assertThat(crop.height()).isGreaterThan(55);
    }

    @Test
    void cropsBboxWithSafetyMargin() throws Exception {
        ImageProcessor processor = new ImageProcessor(new VisionCartProperties());

        ImageProcessor.CroppedImage crop = processor.cropToJpeg(imageBytes(100, 100), List.of(25, 25, 75, 75));

        assertThat(crop.width()).isGreaterThanOrEqualTo(70);
        assertThat(crop.height()).isGreaterThanOrEqualTo(70);
    }

    @Test
    void cropsSmallProductRegionWithoutReturningWholeImage() throws Exception {
        ImageProcessor processor = new ImageProcessor(new VisionCartProperties());

        ImageProcessor.CroppedImage crop = processor.cropToJpeg(imageBytes(400, 400), List.of(100, 100, 180, 180));

        assertThat(crop.bytes()).isNotEmpty();
        assertThat(crop.width()).isLessThan(400);
        assertThat(crop.height()).isLessThan(400);
        assertThat(crop.sourceArea()).isEqualTo(6400);
    }

    private AliyunQwenVisionClient client() {
        return new AliyunQwenVisionClient(
                new VisionCartProperties(),
                mock(AiTraceService.class),
                objectMapper,
                mock(PromptLoader.class),
                mock(DoubaoVisionClient.class),
                "test-key",
                "http://localhost",
                RestClient.create()
        );
    }

    private String openAiBody(String content) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "choices", List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", content)
                ))
        ));
    }

    private byte[] imageBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.fillRect(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
