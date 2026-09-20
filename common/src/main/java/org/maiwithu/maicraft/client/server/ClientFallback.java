// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.Consumer;

/** 为指定操作及版本提供行为明确等价的客户端原生执行入口。 */
public interface ClientFallback {
    /** 已登记回退实现仍不够，实际运行环境中也必须存在对应模组或接口。 */
    boolean supported();

    /** 执行前重新检查终端是否可见、交互距离和当前材料等条件。 */
    Availability availability(JsonObject arguments);

    /** 在客户端线程提交一次操作；完成结果可以从其他线程返回。 */
    void submit(UUID requestId, JsonObject arguments, Consumer<ClientRequestReceipt.Result> completion);

    /** 撤销尚未执行的原生工作；已提交的效果仍须报告最终实际结果。 */
    default void cancel(UUID requestId) {}

    record Availability(boolean available, String reason) {
        public Availability { reason = reason == null ? "" : reason; }
        public static Availability ready() { return new Availability(true, ""); }
        public static Availability unavailable(String reason) { return new Availability(false, reason); }
    }
}
