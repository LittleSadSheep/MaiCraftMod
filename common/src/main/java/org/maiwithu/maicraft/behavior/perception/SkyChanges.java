// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.WorldTime;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 天色变化（事件 sky_changed）：天黑、天亮、开始下雨、雨停，只在变的那一刻发一条，LLM 靠它安排睡觉和户外的活。
 *
 * <p>天黑天亮按"到了能睡的时间没有"判断，和夜晚生存需求用的是世界时间规则里同一处：下雨天天黑得早一点，
 * 白天打雷把天压暗到能睡也算（消息写明是雷暴压暗的）。晴雨按观察环境的天气词，和 observe 看到的一致。
 *
 * <p>没有昼夜的维度（下界、末地）不发。刚进世界、刚换维度时服务器的天气要晚一两刻才同步过来，
 * 头两秒只记下当下，不把"一进来就在下雨"当成开始下雨。人自己玩的时候也照样发。
 */
public final class SkyChanges {

    /** 刚进世界、刚换维度后这么多刻只记下当下，不算变：天气包可能比世界晚到。 */
    static final int SETTLE_TICKS = 40;

    private final TaskEventSink events;
    /** 上一眼所在的维度；变了从头记。 */
    private String dimension;
    /** 还要只记不发的刻数。 */
    private int settling;
    /** 上一眼的天：还没看过（刚进来、在没有昼夜的维度）为 null。 */
    private Sky last;

    public SkyChanges(TaskEventSink events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /** 每刻看一眼天：能不能睡、晴雨，和上一眼不同就发一条。 */
    public void tick(PlayerContext context) {
        if (context == null || context.localPlayer() == null || context.level() == null) return;
        ClientLevel level = context.level();
        observe(new Sky(level.dimension().location().toString(), level.dimensionType().hasFixedTime(),
                WorldTime.canAttemptSleep(level), level.getDayTime(),
                ClientEnvironmentSight.weatherWord(level.getRainLevel(1.0F), level.getThunderLevel(1.0F))));
    }

    /**
     * 一眼看到的天。
     *
     * @param fixedTime 这个维度没有昼夜（下界、末地）
     * @param sleepTime 到了能睡的时间
     * @param dayTime   世界时间（刻），消息里报此刻是什么时候
     * @param weather   天气词：雷暴、雨、晴
     */
    record Sky(String dimension, boolean fixedTime, boolean sleepTime, long dayTime, String weather) {

        boolean raining() {
            return !ClientEnvironmentSight.CLEAR.equals(weather);
        }
    }

    void observe(Sky sky) {
        // 换了维度（含刚进世界）：从头记，头两秒只记不发。
        if (!sky.dimension().equals(dimension)) {
            dimension = sky.dimension();
            settling = SETTLE_TICKS;
            last = null;
        }
        // 下界、末地没有昼夜和天气，不看。
        if (sky.fixedTime()) {
            last = null;
            return;
        }
        if (settling > 0) {
            settling--;
            last = sky;
            return;
        }
        if (last != null) {
            // 先说天色，再说晴雨：雷暴压暗天的那一刻两条都可能变。
            if (sky.sleepTime() != last.sleepTime()) {
                events.publish(TaskEvent.Kind.SKY_CHANGED, sky.sleepTime() ? darkened(sky) : brightened(sky, last));
            }
            if (sky.raining() != last.raining()) {
                events.publish(TaskEvent.Kind.SKY_CHANGED, (sky.raining() ? "开始下雨了" : "雨停了") + when(sky));
            }
        }
        last = sky;
    }

    /** 换世界、断线：从头记，下一次进世界那一刻不算变。 */
    public void forget() {
        dimension = null;
        last = null;
        settling = 0;
    }

    // 天黑：白天（一千到一万二千刻）只有雷暴能把天压暗到能睡——光下雨要到傍晚才早黑一点，
    // 而雷声起来到压暗那一刻天气词可能还是"雨"，所以白天下着雨变暗就写雷暴；别的时候就是天黑了。纯函数。
    static String darkened(Sky sky) {
        boolean storm = WorldTime.phase(sky.dayTime()) == WorldTime.Phase.DAY && sky.raining();
        return (storm ? "雷暴把天压暗了" : "天黑了") + "，到了能睡的时间" + when(sky);
    }

    // 天亮：白天下着雨又亮回来是雷暴过去了（雷声小下去时天气词已经先落回"雨"）；睡一觉醒来、熬到凌晨都是天亮了。纯函数。
    static String brightened(Sky sky, Sky before) {
        boolean stormPassed = WorldTime.phase(sky.dayTime()) == WorldTime.Phase.DAY && before.raining();
        return (stormPassed ? "雷暴过去，天又亮了" : "天亮了") + "，过了能睡的时间" + when(sky);
    }

    // 括号里带此刻的时间与天气："（夜晚，距天亮约 9 分钟；晴）"。
    private static String when(Sky sky) {
        return "（" + ClientEnvironmentSight.timeText(sky.dayTime()) + "；" + sky.weather() + "）";
    }
}
