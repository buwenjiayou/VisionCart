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
            try { dbReader.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 判断当前请求是否来自国内。
     * GeoIP 不可用时默认返回 true（安全策略）。
     */
    public boolean isDomestic() {
        if (!available) return true;
        HttpServletRequest request = currentRequest();
        if (request == null) return true;
        String ip = extractClientIp(request);
        return isDomesticIp(ip);
    }

    private boolean isDomesticIp(String ip) {
        if (ip == null || ip.isBlank()) return true;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            // 本地地址直接当国内
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()) return true;
            CountryResponse response = dbReader.country(addr);
            Country country = response.getCountry();
            String isoCode = country.getIsoCode();
            return isoCode == null || CN_COUNTRY_CODES.contains(isoCode);
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for {}: {}", ip, e.getMessage());
            return true; // 查不到默认国内
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
        String remoteAddr = request.getRemoteAddr();
        // Only trust proxy headers when the direct connection is from a trusted proxy (localhost/private)
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
        // 172.16.0.0/12: 172.16.x.x ~ 172.31.x.x
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }
}
