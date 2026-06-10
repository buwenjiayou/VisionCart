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
    private final Security security = new Security();
    private final RateLimit rateLimit = new RateLimit();
    private final Platforms platforms = new Platforms();

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
    public Security getSecurity() { return security; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Platforms getPlatforms() { return platforms; }

    // ==================== AI ====================
    public static class Ai {
        private String llmModel;
        private String visionModel;
        private String visionApiKey;
        private String qwenFlashModel = "qwen3-vl-flash";
        private String qwenPlusModel = "qwen3-vl-plus";
        private int nlpRetryCount = 2;
        private long nlpRetryBaseDelayMs = 1000;
        private double temperature = 0.0;
        private Integer maxTokens;
        private Double topP;
        private boolean semanticJudgeEnabled = true;
        private int semanticJudgeCandidateLimit = 12;
        private int semanticJudgeReturnLimit = 12;
        private double semanticJudgeMinScore = 0.35;

        public String getLlmModel() { return llmModel; }
        public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String visionModel) { this.visionModel = visionModel; }

        public String getVisionApiKey() { return visionApiKey; }
        public void setVisionApiKey(String visionApiKey) { this.visionApiKey = visionApiKey; }

        public String getQwenFlashModel() { return qwenFlashModel; }
        public void setQwenFlashModel(String qwenFlashModel) { this.qwenFlashModel = qwenFlashModel; }

        public String getQwenPlusModel() { return qwenPlusModel; }
        public void setQwenPlusModel(String qwenPlusModel) { this.qwenPlusModel = qwenPlusModel; }

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

        public boolean isSemanticJudgeEnabled() { return semanticJudgeEnabled; }
        public void setSemanticJudgeEnabled(boolean semanticJudgeEnabled) { this.semanticJudgeEnabled = semanticJudgeEnabled; }

        public int getSemanticJudgeCandidateLimit() { return semanticJudgeCandidateLimit; }
        public void setSemanticJudgeCandidateLimit(int semanticJudgeCandidateLimit) { this.semanticJudgeCandidateLimit = semanticJudgeCandidateLimit; }

        public int getSemanticJudgeReturnLimit() { return semanticJudgeReturnLimit; }
        public void setSemanticJudgeReturnLimit(int semanticJudgeReturnLimit) { this.semanticJudgeReturnLimit = semanticJudgeReturnLimit; }

        public double getSemanticJudgeMinScore() { return semanticJudgeMinScore; }
        public void setSemanticJudgeMinScore(double semanticJudgeMinScore) { this.semanticJudgeMinScore = semanticJudgeMinScore; }
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
        private final Reputation reputation = new Reputation();

        public long getPlatformTimeoutMs() { return platformTimeoutMs; }
        public void setPlatformTimeoutMs(long platformTimeoutMs) { this.platformTimeoutMs = platformTimeoutMs; }

        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

        public Reputation getReputation() { return reputation; }

        public static class Reputation {
            private double trustWeight = 0.85;
            private double relevanceWeight = 0.10;
            private double salesWeight = 0.0;
            private double taobaoShopDsrConfidence = 0.75;
            private double tmallShopDsrConfidence = 0.78;
            private double pddShopLevelConfidence = 0.60;
            private double ebaySellerConfidence = 0.70;
            private double unknownShopDsrConfidence = 0.65;
            private double unknownSellerConfidence = 0.65;

            public double getTrustWeight() { return trustWeight; }
            public void setTrustWeight(double trustWeight) { this.trustWeight = trustWeight; }

            public double getRelevanceWeight() { return relevanceWeight; }
            public void setRelevanceWeight(double relevanceWeight) { this.relevanceWeight = relevanceWeight; }

            public double getSalesWeight() { return salesWeight; }
            public void setSalesWeight(double salesWeight) { this.salesWeight = salesWeight; }

            public double getTaobaoShopDsrConfidence() { return taobaoShopDsrConfidence; }
            public void setTaobaoShopDsrConfidence(double taobaoShopDsrConfidence) {
                this.taobaoShopDsrConfidence = taobaoShopDsrConfidence;
            }

            public double getTmallShopDsrConfidence() { return tmallShopDsrConfidence; }
            public void setTmallShopDsrConfidence(double tmallShopDsrConfidence) {
                this.tmallShopDsrConfidence = tmallShopDsrConfidence;
            }

            public double getPddShopLevelConfidence() { return pddShopLevelConfidence; }
            public void setPddShopLevelConfidence(double pddShopLevelConfidence) {
                this.pddShopLevelConfidence = pddShopLevelConfidence;
            }

            public double getEbaySellerConfidence() { return ebaySellerConfidence; }
            public void setEbaySellerConfidence(double ebaySellerConfidence) {
                this.ebaySellerConfidence = ebaySellerConfidence;
            }

            public double getUnknownShopDsrConfidence() { return unknownShopDsrConfidence; }
            public void setUnknownShopDsrConfidence(double unknownShopDsrConfidence) {
                this.unknownShopDsrConfidence = unknownShopDsrConfidence;
            }

            public double getUnknownSellerConfidence() { return unknownSellerConfidence; }
            public void setUnknownSellerConfidence(double unknownSellerConfidence) {
                this.unknownSellerConfidence = unknownSellerConfidence;
            }
        }
    }

    // ==================== Recognition ====================
    public static class Recognition {
        private int imageMaxDimension = 1024;
        private float imageJpegQuality = 0.85f;
        private long maxUploadBytes = 25L * 1024L * 1024L;
        private long timeoutMs = 30000;
        private int retryCount = 1;
        private double blurThreshold = 35.0;
        private int brightnessMin = 15;
        private int brightnessMax = 245;
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 50;
        private long retryBaseDelayMs = 1000;
        private int multiProductThreshold = 2;
        private double minDetectionConfidence = 0.40;
        private int maxProducts = 8;
        private String historyImageDir = "data/recognition-history";
        private Map<String, List<String>> attributeOptions = Map.of();

        public int getImageMaxDimension() { return imageMaxDimension; }
        public void setImageMaxDimension(int imageMaxDimension) { this.imageMaxDimension = imageMaxDimension; }

        public float getImageJpegQuality() { return imageJpegQuality; }
        public void setImageJpegQuality(float imageJpegQuality) { this.imageJpegQuality = imageJpegQuality; }

        public long getMaxUploadBytes() { return maxUploadBytes; }
        public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }

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

        public int getMultiProductThreshold() { return multiProductThreshold; }
        public void setMultiProductThreshold(int multiProductThreshold) { this.multiProductThreshold = multiProductThreshold; }

        public double getMinDetectionConfidence() { return minDetectionConfidence; }
        public void setMinDetectionConfidence(double minDetectionConfidence) { this.minDetectionConfidence = minDetectionConfidence; }

        public int getMaxProducts() { return maxProducts; }
        public void setMaxProducts(int maxProducts) { this.maxProducts = maxProducts; }

        public String getHistoryImageDir() { return historyImageDir; }
        public void setHistoryImageDir(String historyImageDir) { this.historyImageDir = historyImageDir; }

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

    // ==================== Security ====================
    public static class Security {
        private String allowedOrigins = "http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080";
        private String adminUserIds = "";
        private String monitorToken = "";
        private boolean websocketQueryTokenEnabled = false;

        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }

        public String getAdminUserIds() { return adminUserIds; }
        public void setAdminUserIds(String adminUserIds) { this.adminUserIds = adminUserIds; }

        public String getMonitorToken() { return monitorToken; }
        public void setMonitorToken(String monitorToken) { this.monitorToken = monitorToken; }

        public boolean isWebsocketQueryTokenEnabled() { return websocketQueryTokenEnabled; }
        public void setWebsocketQueryTokenEnabled(boolean websocketQueryTokenEnabled) {
            this.websocketQueryTokenEnabled = websocketQueryTokenEnabled;
        }

        public java.util.List<String> allowedOriginList() {
            return java.util.Arrays.stream(java.util.Optional.ofNullable(allowedOrigins).orElse("").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        public boolean isAdmin(Long userId) {
            if (userId == null) {
                return false;
            }
            return java.util.Arrays.stream(java.util.Optional.ofNullable(adminUserIds).orElse("").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .anyMatch(value -> {
                        try {
                            return Long.parseLong(value) == userId;
                        } catch (NumberFormatException ignored) {
                            return false;
                        }
                    });
        }
    }

    // ==================== Rate Limit ====================
    public static class RateLimit {
        private boolean enabled = true;
        private int defaultLimit = 120;
        private int defaultWindowSeconds = 60;
        private int authLimit = 10;
        private int authWindowSeconds = 300;
        private int sendCodeCooldownSeconds = 60;
        private int recognitionLimit = 20;
        private int recognitionWindowSeconds = 3600;
        private int maxLocalKeys = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getDefaultLimit() { return defaultLimit; }
        public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }

        public int getDefaultWindowSeconds() { return defaultWindowSeconds; }
        public void setDefaultWindowSeconds(int defaultWindowSeconds) { this.defaultWindowSeconds = defaultWindowSeconds; }

        public int getAuthLimit() { return authLimit; }
        public void setAuthLimit(int authLimit) { this.authLimit = authLimit; }

        public int getAuthWindowSeconds() { return authWindowSeconds; }
        public void setAuthWindowSeconds(int authWindowSeconds) { this.authWindowSeconds = authWindowSeconds; }

        public int getSendCodeCooldownSeconds() { return sendCodeCooldownSeconds; }
        public void setSendCodeCooldownSeconds(int sendCodeCooldownSeconds) { this.sendCodeCooldownSeconds = sendCodeCooldownSeconds; }

        public int getRecognitionLimit() { return recognitionLimit; }
        public void setRecognitionLimit(int recognitionLimit) { this.recognitionLimit = recognitionLimit; }

        public int getRecognitionWindowSeconds() { return recognitionWindowSeconds; }
        public void setRecognitionWindowSeconds(int recognitionWindowSeconds) { this.recognitionWindowSeconds = recognitionWindowSeconds; }

        public int getMaxLocalKeys() { return maxLocalKeys; }
        public void setMaxLocalKeys(int maxLocalKeys) { this.maxLocalKeys = maxLocalKeys; }
    }

    // ==================== Platforms ====================
    public static class Platforms {
        private Platform pdd = new Platform();
        private Platform taobao = new Platform();
        private Platform ebay = new Platform();

        public Platform getPdd() { return pdd; }
        public void setPdd(Platform pdd) { this.pdd = pdd; }

        public Platform getTaobao() { return taobao; }
        public void setTaobao(Platform taobao) { this.taobao = taobao; }

        public Platform getEbay() { return ebay; }
        public void setEbay(Platform ebay) { this.ebay = ebay; }

        /**
         * 获取指定平台的配置（支持 PlatformSearchService.platform() 返回值和配置键名）
         */
        public Platform getPlatform(String platformName) {
            if (platformName == null) return new Platform();
            return switch (platformName) {
                case "拼多多", "pdd" -> pdd;
                case "淘宝", "淘宝联盟", "taobao" -> taobao;
                case "eBay", "ebay" -> ebay;
                default -> new Platform();
            };
        }

        /**
         * 返回所有平台名与配置的映射，用于遍历
         */
        public java.util.List<java.util.Map.Entry<String, Platform>> all() {
            return java.util.List.of(
                    java.util.Map.entry("拼多多", pdd),
                    java.util.Map.entry("淘宝", taobao),
                    java.util.Map.entry("eBay", ebay)
            );
        }
    }

    /**
     * 单个平台的配置
     */
    public static class Platform {
        private boolean enabled = true;
        private long timeoutMs = 2500;
        private double weight = 1.0;
        private boolean fallbackEnabled = true;
        private int fallbackPriority = 1;
        private String regionStrategy = "global"; // global, domestic, international
        private int maxRetries = 2;
        private long retryDelayMs = 500;
        private int rateLimitPerSecond = 10;
        private boolean circuitBreakerEnabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }

        public boolean isFallbackEnabled() { return fallbackEnabled; }
        public void setFallbackEnabled(boolean fallbackEnabled) { this.fallbackEnabled = fallbackEnabled; }

        public int getFallbackPriority() { return fallbackPriority; }
        public void setFallbackPriority(int fallbackPriority) { this.fallbackPriority = fallbackPriority; }

        public String getRegionStrategy() { return regionStrategy; }
        public void setRegionStrategy(String regionStrategy) { this.regionStrategy = regionStrategy; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public long getRetryDelayMs() { return retryDelayMs; }
        public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

        public int getRateLimitPerSecond() { return rateLimitPerSecond; }
        public void setRateLimitPerSecond(int rateLimitPerSecond) { this.rateLimitPerSecond = rateLimitPerSecond; }

        public boolean isCircuitBreakerEnabled() { return circuitBreakerEnabled; }
        public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) { this.circuitBreakerEnabled = circuitBreakerEnabled; }
    }
}
