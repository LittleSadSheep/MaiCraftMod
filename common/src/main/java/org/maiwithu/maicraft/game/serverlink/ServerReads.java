// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.serverlink;

import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * 向服务端问只读操作的入口：联动模组要客户端读不到的数据（ME 网络的控制器、频道、能量）时经它问。
 * 只问不改：服务端只回答事实，不替角色动手。问是异步的——这一刻交出去，过几刻再来看回答。
 *
 * <p>只在客户端线程上用。操作要在入服前登记（联动入口经登记表声明），没登记的问不了。
 */
public interface ServerReads {

    /** 一个都答不了：离线测试、没接服务端链路时用。 */
    ServerReads NONE = new ServerReads() {
        @Override public boolean available(String operationId) {
            return false;
        }

        @Override public Optional<UUID> ask(String operationId, JsonObject body) {
            return Optional.empty();
        }

        @Override public Optional<ClientRequest.Snapshot> answer(UUID request) {
            return Optional.empty();
        }
    };

    /** 服务端此刻答不答这个操作：握手协商到了、所有者没在策略里关掉。 */
    boolean available(String operationId);

    /** 问一次：交出请求编号；这个操作没登记、此刻答不了给空。 */
    Optional<UUID> ask(String operationId, JsonObject body);

    /** 问过的那一次现在怎样：还在等、答了、被拒了；请求已经退场给空。 */
    Optional<ClientRequest.Snapshot> answer(UUID request);
}
