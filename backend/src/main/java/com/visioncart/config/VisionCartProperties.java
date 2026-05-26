package com.visioncart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "visioncart")
public class VisionCartProperties {
    private String publicBaseUrl = "http://localhost:8080";
    private final Ai ai = new Ai();
    private final Pdd pdd = new Pdd();
    private final Taobao taobao = new Taobao();
    private final Ebay ebay = new Ebay();
    private final Search search = new Search();
    private final Recognition recognition = new Recognition();
    private final Suggestion suggestion = new Suggestion();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final PriceMonitor priceMonitor = new PriceMonitor();

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public Ai getAi() { return ai; }
    public Pdd getPdd() { return pdd; }
    public Taobao getTaobao() { return taobao; }
    public Ebay getEbay() { return ebay; }
    public Search getSearch() { return search; }
    public Recognition getRecognition() { return recognition; }
    public Suggestion getSuggestion() { return suggestion; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public PriceMonitor getPriceMonitor() { return priceMonitor; }

    // ==================== AI ====================
    public static class Ai {
        private String llmModel;
        private String visionModel;
        private String visionApiKey;
        private int nlpRetryCount = 2;
        private long nlpRetryBaseDelayMs = 1000;
        private double temperature = 0.0;
        private Integer maxTokens;
        private Double topP;

        public String getLlmModel() { return llmModel; }
        public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String visionModel) { this.visionModel = visionModel; }

        public String getVisionApiKey() { return visionApiKey; }
        public void setVisionApiKey(String visionApiKey) { this.visionApiKey = visionApiKey; }

        public int getNlpRetryCount() { return nlpRetryCount; }
        public void setNlpRetryCount(int nlpRetryCount) { this.nlpRetryCount = nlpRetryCount; }

        public long getNlpRetryBaseDelayMs() { return nlpRetryBaseDelayMs; }
        public void setNlpRetryBaseDelayMs(long nlpRetryBaseDelayMs) { this.nlpRetryBaseDelayMs = nlpRetryBaseDelayMs; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

        public Double getTopP() { return topP; }
        public void setTopP(Double topP) { this.topP = topP; }
    }

    // ==================== PDD ====================
    public static class Pdd {
        private String apiUrl;
        private String clientId;
        private String clientSecret;
        private String pid;

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public String getPid() { return pid; }
        public void setPid(String pid) { this.pid = pid; }
    }

    // ==================== Taobao ====================
    public static class Taobao {
        private String apiUrl = "https://eco.taobao.com/router/rest";
        private String appKey;
        private String appSecret;
        private String adzoneId;

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }

        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }

        public String getAdzoneId() { return adzoneId; }
        public void setAdzoneId(String adzoneId) { this.adzoneId = adzoneId; }
    }

    // ==================== eBay ====================
    public static class Ebay {
        private String appId;
        private String certId;
        private String devId;
        private String tokenUrl = "https://api.ebay.com/identity/v1/oauth2/token";
        private String browseUrl = "https://api.ebay.com/buy/browse/v1/item_summary/search";
        private String marketplaceId = "EBAY_US";

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }

        public String getCertId() { return certId; }
        public void setCertId(String certId) { this.certId = certId; }

        public String getDevId() { return devId; }
        public void setDevId(String devId) { this.devId = devId; }

        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

        public String getBrowseUrl() { return browseUrl; }
        public void setBrowseUrl(String browseUrl) { this.browseUrl = browseUrl; }

        public String getMarketplaceId() { return marketplaceId; }
        public void setMarketplaceId(String marketplaceId) { this.marketplaceId = marketplaceId; }
    }

    // ==================== Search ====================
    public static class Search {
        private long platformTimeoutMs = 8000;
        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 200;

        public long getPlatformTimeoutMs() { return platformTimeoutMs; }
        public void setPlatformTimeoutMs(long platformTimeoutMs) { this.platformTimeoutMs = platformTimeoutMs; }

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    // ==================== Recognition ====================
    public static class Recognition {
        private int imageMaxDimension = 1024;
        private float imageJpegQuality = 0.85f;
        private long timeoutMs = 30000;
        private int retryCount = 1;
        private double blurThreshold = 100.0;
        private int brightnessMin = 30;
        private int brightnessMax = 225;
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 50;
        private long retryBaseDelayMs = 1000;
        private Map<String, List<String>> attributeOptions = Map.of(
                "颜色", List.of("黑色", "白色", "红色", "蓝色", "深蓝色", "藏青", "灰色", "绿色", "黄色", "粉色", "棕色", "米白色"),
                "品牌", List.of("耐克", "阿迪达斯", "安踏", "李宁", "彪马", "新百伦", "亚瑟士"),
                "款式", List.of("跑鞋", "篮球鞋", "板鞋", "训练鞋", "休闲鞋")
        );

        public int getImageMaxDimension() { return imageMaxDimension; }
        public void setImageMaxDimension(int imageMaxDimension) { this.imageMaxDimension = imageMaxDimension; }

        public float getImageJpegQuality() { return imageJpegQuality; }
        public void setImageJpegQuality(float imageJpegQuality) { this.imageJpegQuality = imageJpegQuality; }

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

        public double getBlurThreshold() { return blurThreshold; }
        public void setBlurThreshold(double blurThreshold) { this.blurThreshold = blurThreshold; }

        public int getBrightnessMin() { return brightnessMin; }
        public void setBrightnessMin(int brightnessMin) { this.brightnessMin = brightnessMin; }

        public int getBrightnessMax() { return brightnessMax; }
        public void setBrightnessMax(int brightnessMax) { this.brightnessMax = brightnessMax; }

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

        public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
        public void setRetryBaseDelayMs(long retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; }

        public Map<String, List<String>> getAttributeOptions() { return attributeOptions; }
        public void setAttributeOptions(Map<String, List<String>> attributeOptions) { this.attributeOptions = attributeOptions; }
    }

    // ==================== Suggestion ====================
    public static class Suggestion {
        private long sessionTtlMinutes = 30;
        private int maxSessions = 10000;

        public long getSessionTtlMinutes() { return sessionTtlMinutes; }
        public void setSessionTtlMinutes(long sessionTtlMinutes) { this.sessionTtlMinutes = sessionTtlMinutes; }

        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
    }

    // ==================== CircuitBreaker ====================
    public static class CircuitBreaker {
        private int failureThreshold = 3;
        private long openDurationMs = 60_000;
        private int halfOpenProbeCount = 3;
        private long slowCallThresholdMs = 5000;
        private double slowCallRateThreshold = 0.5;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }

        public long getOpenDurationMs() { return openDurationMs; }
        public void setOpenDurationMs(long openDurationMs) { this.openDurationMs = openDurationMs; }

        public int getHalfOpenProbeCount() { return halfOpenProbeCount; }
        public void setHalfOpenProbeCount(int halfOpenProbeCount) { this.halfOpenProbeCount = halfOpenProbeCount; }

        public long getSlowCallThresholdMs() { return slowCallThresholdMs; }
        public void setSlowCallThresholdMs(long slowCallThresholdMs) { this.slowCallThresholdMs = slowCallThresholdMs; }

        public double getSlowCallRateThreshold() { return slowCallRateThreshold; }
        public void setSlowCallRateThreshold(double slowCallRateThreshold) { this.slowCallRateThreshold = slowCallRateThreshold; }
    }

    // ==================== PriceMonitor ====================
    public static class PriceMonitor {
        private boolean enabled = true;
        private boolean refreshOnView = true;
        private boolean scheduledEnabled = true;
        private int maxProductsPerRun = 50;
        private int historyRetentionDays = 90;
        private int notificationDedupDays = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isRefreshOnView() { return refreshOnView; }
        public void setRefreshOnView(boolean refreshOnView) { this.refreshOnView = refreshOnView; }

        public boolean isScheduledEnabled() { return scheduledEnabled; }
        public void setScheduledEnabled(boolean scheduledEnabled) { this.scheduledEnabled = scheduledEnabled; }

        public int getMaxProductsPerRun() { return maxProductsPerRun; }
        public void setMaxProductsPerRun(int maxProductsPerRun) { this.maxProductsPerRun = maxProductsPerRun; }

        public int getHistoryRetentionDays() { return historyRetentionDays; }
        public void setHistoryRetentionDays(int historyRetentionDays) { this.historyRetentionDays = historyRetentionDays; }

        public int getNotificationDedupDays() { return notificationDedupDays; }
        public void setNotificationDedupDays(int notificationDedupDays) { this.notificationDedupDays = notificationDedupDays; }
    }
}
