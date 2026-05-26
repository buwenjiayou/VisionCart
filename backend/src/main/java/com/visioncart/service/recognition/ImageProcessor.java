package com.visioncart.service.recognition;

import com.visioncart.config.VisionCartProperties;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Component
public class ImageProcessor {

    private final VisionCartProperties.Recognition config;

    public ImageProcessor(VisionCartProperties properties) {
        this.config = properties.getRecognition();
    }

    public byte[] process(byte[] original) {
        byte[] compressed = compress(original, config.getImageMaxDimension(), config.getImageJpegQuality());
        QualityCheckResult check = checkQuality(compressed);
        if (!check.passed()) {
            throw new ImageQualityException(check.reason());
        }
        return compressed;
    }

    public byte[] compress(byte[] original, int maxDimension, float jpegQuality) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                ImageReadParam param = reader.getDefaultReadParam();
                BufferedImage image = reader.read(0, param);

                int orientation = getExifOrientation(reader);
                image = applyExifRotation(image, orientation);

                int origW = image.getWidth();
                int origH = image.getHeight();

                if (origW <= maxDimension && origH <= maxDimension) {
                    return encodeJpeg(image, jpegQuality);
                }

                double scale = (double) maxDimension / Math.max(origW, origH);
                int newW = (int) (origW * scale);
                int newH = (int) (origH * scale);

                BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(image, 0, 0, newW, newH, null);
                g.dispose();

                return encodeJpeg(resized, jpegQuality);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new RuntimeException("Image compression failed", e);
        }
    }

    public QualityCheckResult checkQuality(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return new QualityCheckResult(false, "blurry");
            }

            double laplacianVariance = computeLaplacianVariance(image);
            if (laplacianVariance < config.getBlurThreshold()) {
                return new QualityCheckResult(false, "blurry");
            }

            double meanBrightness = computeMeanBrightness(image);
            if (meanBrightness < config.getBrightnessMin()) {
                return new QualityCheckResult(false, "too_dark");
            }
            if (meanBrightness > config.getBrightnessMax()) {
                return new QualityCheckResult(false, "too_bright");
            }

            return new QualityCheckResult(true, null);
        } catch (IOException e) {
            return new QualityCheckResult(false, "blurry");
        }
    }

    private double computeLaplacianVariance(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        double[] laplacian = new double[w * h];

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int center = getGray(image, x, y);
                int top = getGray(image, x, y - 1);
                int bottom = getGray(image, x, y + 1);
                int left = getGray(image, x - 1, y);
                int right = getGray(image, x + 1, y);
                laplacian[y * w + x] = -4.0 * center + top + bottom + left + right;
            }
        }

        double sum = 0;
        double sumSq = 0;
        int count = (w - 2) * (h - 2);
        for (int i = 0; i < laplacian.length; i++) {
            sum += laplacian[i];
            sumSq += laplacian[i] * laplacian[i];
        }
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    private double computeMeanBrightness(BufferedImage image) {
        long sum = 0;
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                sum += getGray(image, x, y);
            }
        }
        return (double) sum / (w * h);
    }

    private int getGray(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.close();
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private int getExifOrientation(ImageReader reader) {
        try {
            var metadata = reader.getImageMetadata(0);
            if (metadata == null) return 1;
            var formatNames = metadata.getMetadataFormatNames();
            if (formatNames == null || formatNames.length == 0) return 1;
            var root = metadata.getAsTree(formatNames[0]);
            if (root instanceof javax.imageio.metadata.IIOMetadataNode node) {
                var orientations = node.getElementsByTagName("Orientation");
                if (orientations.getLength() > 0) {
                    return Integer.parseInt(orientations.item(0).getTextContent());
                }
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private BufferedImage applyExifRotation(BufferedImage image, int orientation) {
        if (orientation <= 1) return image;

        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform transform = new AffineTransform();

        switch (orientation) {
            case 2 -> transform.scale(-1, 1); // flip horizontal
            case 3 -> transform.rotate(Math.PI, w / 2.0, h / 2.0); // 180
            case 4 -> { transform.scale(1, -1); transform.translate(0, -h); } // flip vertical
            case 5 -> { transform.rotate(Math.PI / 2, w / 2.0, h / 2.0); transform.scale(-1, 1); }
            case 6 -> transform.rotate(Math.PI / 2, w / 2.0, h / 2.0); // 90 CW
            case 7 -> { transform.rotate(-Math.PI / 2, w / 2.0, h / 2.0); transform.scale(-1, 1); }
            case 8 -> transform.rotate(-Math.PI / 2, w / 2.0, h / 2.0); // 90 CCW
            default -> { return image; }
        }

        boolean swap = orientation >= 5;
        int newW = swap ? h : w;
        int newH = swap ? w : h;
        BufferedImage rotated = new BufferedImage(newW, newH, image.getType());
        Graphics2D g = rotated.createGraphics();
        g.setTransform(transform);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rotated;
    }

    public record QualityCheckResult(boolean passed, String reason) {
    }
}
