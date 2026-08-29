package com.okututor.backend.auth;

import jakarta.servlet.http.HttpServletRequest;

/** Метаданные сессии: устройство, User-Agent и IP, с которого выдали токен. */
public record SessionInfo(String device, String userAgent, String ip) {

    private static final int MAX_DEVICE_LENGTH = 64;

    public static SessionInfo of(HttpServletRequest request, com.okututor.backend.common.web.ClientIpResolver ipResolver) {
        if (request == null) {
            return new SessionInfo(null, null, null);
        }
        String ua = request.getHeader("User-Agent");
        String device = ua == null ? null
                : ua.length() <= MAX_DEVICE_LENGTH ? ua : ua.substring(0, MAX_DEVICE_LENGTH);
        return new SessionInfo(device, ua, ipResolver.resolve(request));
    }

    /** Пустая сессия (например, для системных операций). */
    public static SessionInfo empty() {
        return new SessionInfo(null, null, null);
    }
}
