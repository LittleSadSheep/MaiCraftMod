// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 保存暂停的端点调查供确认后继续，取用一次就移除；取消时停止保留的调查。当前没有另设数量或过期上限，依赖父任务清理。
 */
final class CreateEndpointContinuations {
    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();

    record Entry(
            UUID token,
            CreateMechanicalPower.Request request,
            CreateProgressiveSurvey survey,
            long bodyEpoch,
            String dimension, boolean economicAfterEndpoints) {}

    private CreateEndpointContinuations() {}

    static synchronized Entry issue(
            CreateMechanicalPower.Request request,
            CreateProgressiveSurvey survey,
            long bodyEpoch,
            String dimension) {
        return issue(request, survey, bodyEpoch, dimension, false);
    }
    static synchronized Entry issue(CreateMechanicalPower.Request request, CreateProgressiveSurvey survey,
            long bodyEpoch, String dimension, boolean economicAfterEndpoints) {
        survey.pause();
        UUID token = UUID.randomUUID();
        Entry entry = new Entry(token, request, survey, bodyEpoch, dimension, economicAfterEndpoints);
        ENTRIES.put(token, entry);
        return entry;
    }

    static synchronized Entry take(UUID token) {
        return ENTRIES.remove(token);
    }

    static synchronized void discard(UUID token) {
        Entry entry = ENTRIES.remove(token);
        if (entry != null) entry.survey().stop();
    }
}
