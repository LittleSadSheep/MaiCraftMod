// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;

/** 准备和观察只读当前玩家；只有 submit 发送一次原生请求，替身可据此检查消费次数与实际回执。 */
public interface FtbQuestActionAccess {
    record Prepared(Object scope, JsonObject before, boolean alreadySatisfied, Object nativeRequest) {}
    Prepared prepare(FtbQuestActionRequest request);
    void submit(Prepared prepared);
    JsonObject observe(Prepared prepared);
}
