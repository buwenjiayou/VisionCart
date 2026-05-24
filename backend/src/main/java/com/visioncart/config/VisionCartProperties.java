package com.visioncart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "visioncart")
public class VisionCartProperties {
    private String publicBaseUrl = "http://localhost:8080";
    private final Ai ai = new Ai();
    private final Pdd pdd = new Pdd();
    private final Taobao taobao = new Taobao();
    private final Ebay ebay = new Ebay();

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public Ai getAi() { return ai; }
    public Pdd getPdd() { return pdd; }
    public Taobao getTaobao() { return taobao; }
    public Ebay getEbay() { return ebay; }

    // ==================== AI ====================
    public static class Ai {
        private String llmModel;
        private String visionModel;
        private String visionApiKey;

        public String getLlmModel() { return llmModel; }
        public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String visionModel) { this.visionModel = visionModel; }

        public String getVisionApiKey() { return visionApiKey; }
        public void setVisionApiKey(String visionApiKey) { this.visionApiKey = visionApiKey; }
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
}
