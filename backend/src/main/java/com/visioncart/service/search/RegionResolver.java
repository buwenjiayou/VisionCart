package com.visioncart.service.search;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.File;
import java.net.InetAddress;
import java.util.Set;

@Component
public class RegionResolver {

    private static final Logger log = LoggerFactory.getLogger(RegionResolver.class);
    private static final Set<String> CN_COUNTRY_CODES = Set.of("CN", "HK", "MO", "TW");

    @Value("${visioncart.geoip.database-path:}")
    private String databasePath;

    private DatabaseReader dbReader;
    private boolean available = false;

    @PostConstruct
    void init() {
        if (databasePath == null || databasePath.isBlank()) {
            log.warn("GeoIP database not configured (visioncart.geoip.database-path). Region detection disabled, defaulting to domestic.");
            return;
        }
        File dbFile = new File(databasePath);
        if (!dbFile.exists()) {
            log.warn("GeoIP database not found at {}. Defaulting to domestic.", databasePath);
            return;
        }
        try {
            dbReader = new DatabaseReader.Builder(dbFile).build();
            available = true;
            log.info("GeoIP database loaded from {}", databasePath);
        } catch (Exception e) {
            log.error("Failed to load GeoIP database: {}", e.getMessage());
        }
    }

    @PreDestroy
    void destroy() {
        if (dbReader != null) {
            try {
                dbReader.close();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isDomestic() {
        return resolve(null).domestic();
    }

    public RegionDecision resolve(String regionMode) {
        String requestedMode = normalizeRegionMode(regionMode);
        HttpServletRequest request = currentRequest();
        String remoteAddr = request == null ? "" : safe(request.getRemoteAddr());
        String xForwardedFor = request == null ? "" : safe(request.getHeader("X-Forwarded-For"));
        String xRealIp = request == null ? "" : safe(request.getHeader("X-Real-IP"));
        String clientIp = request == null ? "" : extractClientIp(request);
        GeoLookup lookup = lookup(clientIp);

        if ("domestic".equals(requestedMode)) {
            return new RegionDecision(requestedMode, "domestic", true, clientIp, remoteAddr,
                    xForwardedFor, xRealIp, lookup.countryCode(), "explicit");
        }
        if ("international".equals(requestedMode)) {
            return new RegionDecision(requestedMode, "international", false, clientIp, remoteAddr,
                    xForwardedFor, xRealIp, lookup.countryCode(), "explicit");
        }
        return new RegionDecision(requestedMode, lookup.domestic() ? "domestic" : "international",
                lookup.domestic(), clientIp, remoteAddr, xForwardedFor, xRealIp,
                lookup.countryCode(), "geoip");
    }

    public static String normalizeRegionMode(String regionMode) {
        if (regionMode == null || regionMode.isBlank()) {
            return "auto";
        }
        String normalized = regionMode.trim().toLowerCase();
        return switch (normalized) {
            case "domestic", "cn", "china" -> "domestic";
            case "international", "overseas", "global", "intl", "ebay" -> "international";
            default -> "auto";
        };
    }

    private GeoLookup lookup(String ip) {
        if (!available) return new GeoLookup("UNAVAILABLE", true);
        if (ip == null || ip.isBlank()) return new GeoLookup("UNKNOWN", true);
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()) {
                return new GeoLookup("LOCAL", true);
            }
            CountryResponse response = dbReader.country(addr);
            Country country = response.getCountry();
            String isoCode = country.getIsoCode();
            return new GeoLookup(isoCode == null ? "UNKNOWN" : isoCode,
                    isoCode == null || CN_COUNTRY_CODES.contains(isoCode));
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for {}: {}", ip, e.getMessage());
            return new GeoLookup("LOOKUP_FAILED", true);
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String remoteAddr = safe(request.getRemoteAddr());
        if (isTrustedProxy(remoteAddr)) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) {
                return ip.split(",")[0].trim();
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isBlank()) {
                return ip.trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        if (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1")) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record GeoLookup(String countryCode, boolean domestic) {
    }

    public record RegionDecision(
            String requestedMode,
            String effectiveMode,
            boolean domestic,
            String clientIp,
            String remoteAddr,
            String xForwardedFor,
            String xRealIp,
            String countryCode,
            String source
    ) {
    }
}
