// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;

/** 发送写操作前先持久化未决记录；保存失败时禁止提交新的游戏修改。 */
public interface MutationPersistence {
    void beforeSubmission(ClientRequestReceipt receipt);
    void afterObservation(ClientRequestReceipt receipt);
    boolean unresolved();
    JsonObject report();

    MutationPersistence NONE = new MutationPersistence() {
        public void beforeSubmission(ClientRequestReceipt receipt) {}
        public void afterObservation(ClientRequestReceipt receipt) {}
        public boolean unresolved() { return false; }
        public JsonObject report() { return new JsonObject(); }
    };
}
