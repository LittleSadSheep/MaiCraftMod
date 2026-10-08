// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuSession.Claim;
import org.maiwithu.maicraft.behavior.menu.MenuSession.Closing;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 界面会话：光标为空才认领、不是自己拿的不回退、自己拿的放回源格再关、移交后不关。 */
class MenuSessionTest {

    // 界面通道替身：开关、光标与点击记录都按测试脚本拨动。
    private static final class FakeChannel implements MenuChannel {
        boolean open = true;
        boolean cursorCarrying;
        final List<String> clicks = new ArrayList<>();
        int closeRequests;

        @Override public boolean stillOpen() { return open; }
        @Override public boolean cursorCarrying() { return cursorCarrying; }
        @Override public void click(int slot, int button) { clicks.add(slot + ":" + button); }
        @Override public void requestClose() { closeRequests++; }
    }

    @Test
    void 界面没真的打开不认领() {
        FakeChannel channel = new FakeChannel();
        channel.open = false;
        Claim.Refused refused = assertInstanceOf(Claim.Refused.class, MenuSession.claim(channel));
        assertEquals(Problem.Kind.TARGET_GONE, refused.problem().kind());
    }

    @Test
    void 光标有物不认领() {
        FakeChannel channel = new FakeChannel();
        channel.cursorCarrying = true;
        Claim.Refused refused = assertInstanceOf(Claim.Refused.class, MenuSession.claim(channel));
        assertEquals(Problem.Kind.INTERNAL_ERROR, refused.problem().kind());
        assertEquals(0, channel.clicks.size(), "认领失败不动任何槽位");
    }

    @Test
    void 光标为空就认领() {
        Claim claim = MenuSession.claim(new FakeChannel());
        MenuSession session = assertInstanceOf(Claim.Owned.class, claim).session();
        assertFalse(session.handedOver());
    }

    @Test
    void 不是自己拿的物品不回退直接关() {
        FakeChannel channel = new FakeChannel();
        MenuSession session = ((Claim.Owned) MenuSession.claim(channel)).session();
        // 光标上的东西是别的使用者留下的：不点击任何槽位，原样关掉。
        channel.cursorCarrying = true;
        Closing first = session.closeNow(channel, 0);
        assertInstanceOf(Closing.InProgress.class, first);
        assertEquals(0, channel.clicks.size());
        assertEquals(1, channel.closeRequests);
        // 界面消失后下一刻收尾完成。
        channel.open = false;
        assertInstanceOf(Closing.Closed.class, session.closeNow(channel, 1));
    }

    @Test
    void 自己拿起的物品先放回源格再关() {
        FakeChannel channel = new FakeChannel();
        MenuSession session = ((Claim.Owned) MenuSession.claim(channel)).session();
        session.noteCursorTakenFrom(17);
        channel.cursorCarrying = true;
        // 收尾第一步：把光标上的东西放回拿起的那个源格，先不关。
        assertInstanceOf(Closing.InProgress.class, session.closeNow(channel, 0));
        assertEquals(List.of("17:0"), channel.clicks);
        assertEquals(0, channel.closeRequests);
        // 光标清空之后才请求关闭；界面消失后收尾完成。
        channel.cursorCarrying = false;
        assertInstanceOf(Closing.InProgress.class, session.closeNow(channel, 1));
        assertEquals(1, channel.closeRequests);
        channel.open = false;
        assertInstanceOf(Closing.Closed.class, session.closeNow(channel, 2));
    }

    @Test
    void 到期限关不上带着最早的问题结束() {
        FakeChannel channel = new FakeChannel();
        MenuSession session = ((Claim.Owned) MenuSession.claim(channel)).session();
        session.keepEarliest(Problem.of(Problem.Kind.REFUSED_BY_GAME, "搬运时被服务器拒绝"));
        Closing closing = session.closeNow(channel, 0);
        assertInstanceOf(Closing.InProgress.class, closing);
        // 过了期限界面还开着：失败里带的是最早的问题，不是关页超时。
        Closing.Failed failed = assertInstanceOf(Closing.Failed.class, session.closeNow(channel, 101));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, failed.problem().kind());
    }

    @Test
    void 移交之后不再负责关闭() {
        FakeChannel channel = new FakeChannel();
        MenuSession session = ((Claim.Owned) MenuSession.claim(channel)).session();
        session.handOver();
        assertTrue(session.handedOver());
        Closing.Failed failed = assertInstanceOf(Closing.Failed.class, session.closeNow(channel, 0));
        assertEquals(Problem.Kind.INTERNAL_ERROR, failed.problem().kind());
        assertEquals(0, channel.closeRequests);
    }

    @Test
    void 下一笔点击前的核对() {
        FakeChannel channel = new FakeChannel();
        MenuSession session = ((Claim.Owned) MenuSession.claim(channel)).session();
        assertTrue(session.readyForNextClick(channel));
        channel.cursorCarrying = true;
        assertFalse(session.readyForNextClick(channel), "光标为空才动手");
        channel.cursorCarrying = false;
        channel.open = false;
        assertFalse(session.readyForNextClick(channel), "界面不在了不再点击");
    }
}
