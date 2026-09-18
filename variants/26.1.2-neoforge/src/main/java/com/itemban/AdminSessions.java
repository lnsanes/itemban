package com.itemban;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 通过管理模组哈希核对后的短时会话，避免每个按钮再走一轮挑战。 */
final class AdminSessions {
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final ConcurrentHashMap<UUID, Long> UNTIL = new ConcurrentHashMap<>();

    private AdminSessions() {}

    static void authorize(UUID uuid) {
        UNTIL.put(uuid, System.currentTimeMillis() + TTL_MS);
    }

    static boolean isAuthorized(UUID uuid) {
        Long until = UNTIL.get(uuid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            UNTIL.remove(uuid);
            return false;
        }
        return true;
    }

    static void clear(UUID uuid) {
        if (uuid != null) {
            UNTIL.remove(uuid);
        }
    }

    static void clear() {
        UNTIL.clear();
    }
}
