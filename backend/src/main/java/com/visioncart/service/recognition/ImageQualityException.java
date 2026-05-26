package com.visioncart.service.recognition;

public class ImageQualityException extends RuntimeException {

    private final String reason;

    public ImageQualityException(String reason) {
        super(buildMessage(reason));
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    private static String buildMessage(String reason) {
        return switch (reason) {
            case "blurry" -> "请重新拍照：图片模糊";
            case "too_dark" -> "请重新拍照：光线不足";
            case "too_bright" -> "请重新拍照：光线过强";
            default -> "请重新拍照：图片质量不佳";
        };
    }
}
