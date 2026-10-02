// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import org.maiwithu.maicraft.server.machine.NativeApi;

/** 任务书发包也占用身体本刻的原生操作机会，玩家归还控制或旧入口失效后不得继续提交。 */
public final class FtbQuestSubmission {
    private FtbQuestSubmission() {}
    public static void requireAuthority(LocalPlayerContext context) { current(context).requireSubmissionAuthority(); }
    public static void send(LocalPlayerContext context, Object payload) {
        submit(context, () -> NativeApi.call(null, "dev.architectury.networking.NetworkManager", "sendToServer", payload));
    }
    static void submit(LocalPlayerContext context, Runnable dispatch) {
        // 先占用动作机会再发包；发送中异常也不能在同一刻偷换成第二次消费。
        current(context).claimMutation(); dispatch.run();
    }
    private static DefaultLocalPlayerContext current(LocalPlayerContext context) {
        if (!(context instanceof DefaultLocalPlayerContext current)) throw new IllegalArgumentException("FTB submission requires the native actor context");
        return current;
    }
}
