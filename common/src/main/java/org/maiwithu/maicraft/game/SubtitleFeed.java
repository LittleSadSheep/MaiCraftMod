// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;

/**
 * 原版字幕事件的接收端：游戏每播一个带字幕提示的声音，这里记下字幕文字与大致来源，
 * 供感知的 {@code SubtitleEar} 读取。声音只给大致来源——接收端内部的位置只用来
 * 换算方位与远近，观察视图不对外报坐标；听见的和看见的分开，墙后的东西靠它知道"就在附近"。
 *
 * <p>实例在启动时创建并登记到 Mixin 的静态登记点；进世界后由 Mixin 把它挂到原版的声音
 * 事件监听上，退世界时由 Mixin 解除挂接，换维度后重新挂。声音回调只在客户端线程发生，
 * 记录不额外加锁。
 */
public final class SubtitleFeed implements SoundEventListener {

    /** 一条刚播出的字幕声音：字幕文字、大致来源与收到时刻（毫秒时钟）。 */
    public record Played(String kind, Vec3 approximateAt, long receivedMillis) {}

    // 字幕只有"最近一小会儿"的意义：超过保留窗口或超过条数上限的旧事件在写入时清掉。
    private static final int MAX_EVENTS = 64;
    private static final long RETENTION_MILLIS = 3_000L;

    private List<Played> events = List.of();
    private boolean attached;

    /** 进世界后把接收端挂到原版声音事件上；只挂一次，重复调用不重复登记。 */
    public void attach(Minecraft minecraft) {
        if (!attached && minecraft.level != null) {
            minecraft.getSoundManager().addListener(this);
            attached = true;
        }
    }

    /** 退世界后解除挂接：下一维度重新挂。 */
    public void detach(Minecraft minecraft) {
        if (attached) {
            minecraft.getSoundManager().removeListener(this);
            attached = false;
        }
    }

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents accessor, float range) {
        // 与原版字幕层同判：没有字幕文字的声音不值得当观察事实。
        if (accessor == null || accessor.getSubtitle() == null) {
            return;
        }
        String kind = accessor.getSubtitle().getString();
        if (kind.isBlank()) {
            return;
        }
        Played played = new Played(kind,
                new Vec3(sound.getX(), sound.getY(), sound.getZ()), System.currentTimeMillis());
        events = addAndPrune(events, played, played.receivedMillis());
    }

    /** 保留窗口内的字幕声音，旧在前新在后；读一次顺手清一次窗口外的旧事件。 */
    public List<Played> recent() {
        List<Played> kept = retainRecent(events, System.currentTimeMillis());
        events = kept;
        return kept;
    }

    // 窗口外与超量的旧事件丢弃：纯函数，离线测试直接喂列表。
    static List<Played> addAndPrune(List<Played> events, Played played, long nowMillis) {
        List<Played> kept = new ArrayList<>(events);
        kept.add(played);
        return prune(kept, nowMillis);
    }

    // 只留窗口内的事件，超量丢最旧的。
    private static List<Played> prune(List<Played> events, long nowMillis) {
        List<Played> kept = new ArrayList<>();
        for (Played played : events) {
            if (nowMillis - played.receivedMillis() <= RETENTION_MILLIS) {
                kept.add(played);
            }
        }
        while (kept.size() > MAX_EVENTS) {
            kept.remove(0);
        }
        return List.copyOf(kept);
    }

    // 读取时按当前时刻过滤；写入时刻与读取时刻用同一毫秒时钟。
    static List<Played> retainRecent(List<Played> events, long nowMillis) {
        return prune(events, nowMillis);
    }
}
