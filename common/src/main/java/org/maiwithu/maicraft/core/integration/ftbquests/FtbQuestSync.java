// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import java.lang.ref.WeakReference;

/** 记录原生任务书同步属于哪个连接；断线后残留的 ClientQuestFile 不能冒充新服务器的数据。 */
public final class FtbQuestSync {
    private static WeakReference<Object> syncedFile = new WeakReference<>(null), syncedConnection = new WeakReference<>(null);
    private FtbQuestSync() {}

    // 只在 FTB 收到并替换任务书后记录身份，普通资料读取和打开界面都不能补造同步证明。
    public static void received(Object file, Object connection) {
        syncedFile = new WeakReference<>(file); syncedConnection = new WeakReference<>(connection);
    }
    static boolean matches(Object file, Object connection) {
        return file != null && connection != null && file == syncedFile.get() && connection == syncedConnection.get();
    }
}
