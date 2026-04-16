package com.admin;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AdminSessionManager {

    public static final String COOKIE_NAME = "JMAGNIFIER_SESSION";

    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    private final long timeoutMillis;

    public AdminSessionManager(int sessionTimeoutMinutes) {
        this.timeoutMillis = Math.max(1, sessionTimeoutMinutes) * 60L * 1000L;
    }

    public String createSession() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String sessionId = toHex(bytes);
        sessions.put(sessionId, Instant.now().toEpochMilli() + timeoutMillis);
        return sessionId;
    }

    public boolean isValid(String sessionId) {
        if (sessionId == null || sessionId.length() == 0) {
            return false;
        }
        Long expiresAt = sessions.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        long now = Instant.now().toEpochMilli();
        if (expiresAt < now) {
            sessions.remove(sessionId);
            return false;
        }
        sessions.put(sessionId, now + timeoutMillis);
        return true;
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
