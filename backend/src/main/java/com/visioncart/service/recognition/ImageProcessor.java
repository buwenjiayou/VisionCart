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
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
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
            if (iis == null) {
                throw new ImageQualityException("unsupported_format");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new ImageQualityException("unsupported_format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                ImageReadParam param = reader.getDefaultReadParam();
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new ImageQualityException("decode_failed");
                }

                int orientation = getExifOrientation(reader);
                image = applyExifRotation(image, orientation);
                image = toRgb(image);

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
        } catch (ImageQualityException e) {
            throw e;
        } catch (IOException e) {
            throw new ImageQualityException("decode_failed");
        }
    }

    public CroppedImage cropToJpeg(byte[] imageBytes, List<Integer> bbox) {
        if (bbox == null || bbox.size() < 4) {
            throw new ImageQualityException("invalid_bbox");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                throw new ImageQualityException("decode_failed");
            }
            image = toRgb(image);
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();

            int x1 = clamp(bbox.get(0), 0, imageWidth - 1);
            int y1 = clamp(bbox.get(1), 0, imageHeight - 1);
            int x2 = clamp(bbox.get(2), 0, imageWidth);
            int y2 = clamp(bbox.get(3), 0, imageHeight);
            if (x2 <= x1) {
                int tmp = x1;
                x1 = Math.max(0, x2);
                x2 = Math.min(imageWidth, tmp);
            }
            if (y2 <= y1) {
                int tmp = y1;
                y1 = Math.max(0, y2);
                y2 = Math.min(imageHeight, tmp);
            }

            int width = x2 - x1;
            int height = y2 - y1;
            long area = (long) width * height;
            long minArea = Math.max(256L, Math.round(imageWidth * imageHeight * 0.01));
            if (width < 12 || height < 12 || area < minArea) {
                throw new ImageQualityException("invalid_bbox");
            }

            int padX = Math.max(4, Math.round(width * 0.04f));
            int padY = Math.max(4, Math.round(height * 0.04f));
            int cropX = Math.max(0, x1 - padX);
            int cropY = Math.max(0, y1 - padY);
            int cropRight = Math.min(imageWidth, x2 + padX);
            int cropBottom = Math.min(imageHeight, y2 + padY);
            BufferedImage crop = image.getSubimage(cropX, cropY, cropRight - cropX, cropBottom - cropY);
            return new CroppedImage(encodeJpeg(crop, config.getImageJpegQuality()), crop.getWidth(), crop.getHeight(), area);
        } catch (ImageQualityException e) {
            throw e;
        } catch (IOException e) {
            throw new ImageQualityException("decode_failed");
        }
    }

    public QualityCheckResult checkQuality(byte[] imageBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return new QualityCheckResult(false, "decode_failed");
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
            return new QualityCheckResult(false, "decode_failed");
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

    private BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
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
        boolean swap = orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8;
        int newW = swap ? h : w;
        int newH = swap ? w : h;

        switch (orientation) {
            case 2 -> { transform.translate(w, 0); transform.scale(-1, 1); }
            case 3 -> { transform.translate(w, h); transform.rotate(Math.PI); }
            case 4 -> { transform.translate(0, h); transform.scale(1, -1); }
            case 5 -> { transform.rotate(Math.PI / 2); transform.scale(1, -1); }
            case 6 -> { transform.translate(h, 0); transform.rotate(Math.PI / 2); }
            case 7 -> { transform.translate(h, w); transform.rotate(Math.PI / 2); transform.scale(-1, 1); }
            case 8 -> { transform.translate(0, w); transform.rotate(-Math.PI / 2); }
            default -> { return image; }
        }

        BufferedImage rotated = new BufferedImage(newW, newH, image.getType() == 0 ? BufferedImage.TYPE_INT_RGB : image.getType());
        Graphics2D g = rotated.createGraphics();
        g.setTransform(transform);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rotated;
    }

    public record QualityCheckResult(boolean passed, String reason) {
    }

    public record CroppedImage(byte[] bytes, int width, int height, long sourceArea) {
    }
}
