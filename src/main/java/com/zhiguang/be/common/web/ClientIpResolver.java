package com.zhiguang.be.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Component
public class ClientIpResolver {

    private final List<String> trustedProxyPrefixes;

    public ClientIpResolver(@Value("${security.trusted-proxy-prefixes:127.,0:0:0:0:0:0:0:1,::1}") String trustedProxyPrefixes) {
        this.trustedProxyPrefixes = parsePrefixes(trustedProxyPrefixes);
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remoteAddr = normalize(request.getRemoteAddr());
        if (isTrustedProxy(remoteAddr)) {
            String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
            if (StringUtils.hasText(forwardedFor)) {
                return forwardedFor;
            }
            String realIp = firstHeaderValue(request.getHeader("X-Real-IP"));
            if (StringUtils.hasText(realIp)) {
                return realIp;
            }
        }
        return StringUtils.hasText(remoteAddr) ? remoteAddr : "unknown";
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (!StringUtils.hasText(remoteAddr)) {
            return false;
        }
        if (isLoopback(remoteAddr)) {
            return true;
        }
        for (String prefix : trustedProxyPrefixes) {
            if (StringUtils.hasText(prefix) && remoteAddr.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLoopback(String remoteAddr) {
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    private String firstHeaderValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String first = value.split(",", 2)[0].trim();
        return StringUtils.hasText(first) ? first : null;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private List<String> parsePrefixes(String raw) {
        List<String> prefixes = new ArrayList<String>();
        if (!StringUtils.hasText(raw)) {
            return prefixes;
        }
        for (String item : raw.split(",")) {
            String prefix = item.trim();
            if (StringUtils.hasText(prefix)) {
                prefixes.add(prefix);
            }
        }
        return prefixes;
    }
}
