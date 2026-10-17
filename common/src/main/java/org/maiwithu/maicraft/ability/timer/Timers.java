// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.timer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;

import org.maiwithu.maicraft.behavior.perception.ClientEnvironmentSight;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 挂着的计时：LLM 用 timer 设下"过 N 秒叫我"，到点在任务事件流里发一条 timer_due，角色这期间照常干别的。
 *
 * <p>按世界的游戏时间（{@code getGameTime}，每秒 20 刻）计：服务器卡了、单人游戏按 Esc 暂停了，计时跟着慢、跟着停，
 * 和炉子烧东西、作物长大用的是同一只钟；睡觉跳过夜晚、/time set 改的是昼夜时间，不影响它。换世界、断线时
 * 没到点的全部清掉，不带进下一个世界。客户端全程一份，计时编号只增不减，换过世界也不会和旧编号撞上。
 */
public final class Timers {

    /** 同时最多挂几个：够安排"看炉子、等作物、回去收刷怪塔"这类几件事，再多 LLM 自己也记不过来。 */
    public static final int MAX_PENDING = 8;
    /** 每秒的游戏刻数。 */
    static final long TICKS_PER_SECOND = 20;

    private final TaskEventSink events;
    private final List<Timer> pending = new ArrayList<>();
    private int nextId = 1;

    public Timers(TaskEventSink events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * 一个挂着的计时。
     *
     * @param id           计时编号，从 1 起
     * @param afterSeconds 设的时候说过多少秒叫
     * @param dueTick      到点的游戏时刻
     * @param note         设的时候写的备注，到点原样带回
     */
    public record Timer(int id, int afterSeconds, long dueTick, String note) {

        /** 离到点还有几秒：不足一秒算一秒，到了算 0，不报负数。 */
        public long remainingSeconds(long now) {
            return (Math.max(0, dueTick - now) + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
        }
    }

    /** 设一个计时；已经挂满时为空，交给能力如实拒。 */
    public Optional<Timer> set(int afterSeconds, String note, long now) {
        if (pending.size() >= MAX_PENDING) return Optional.empty();
        Timer timer = new Timer(nextId++, afterSeconds, now + afterSeconds * TICKS_PER_SECOND, note);
        pending.add(timer);
        return Optional.of(timer);
    }

    /** 按编号取消一个还挂着的计时；没有这个编号（已经到点、换过世界、写错了）时为空。 */
    public Optional<Timer> cancel(int id) {
        Optional<Timer> found = pending.stream().filter(timer -> timer.id() == id).findFirst();
        found.ifPresent(pending::remove);
        return found;
    }

    /** 还挂着的计时，先到点的排前面。 */
    public List<Timer> pending() {
        return pending.stream().sorted(Comparator.comparingLong(Timer::dueTick)).toList();
    }

    /** 每刻看一眼钟：到点的发事件并拿掉。人自己玩的时候也照样叫，计时是 LLM 自己的事，不因控制权换手断掉。 */
    public void tick(PlayerContext context) {
        if (pending.isEmpty() || context == null || context.localPlayer() == null || context.level() == null) return;
        ClientLevel level = context.level();
        fireDue(level.getGameTime(), ClientEnvironmentSight.timeText(level.getDayTime()));
    }

    /**
     * 发出到点的计时：先到点的先发，一个一条，发完拿掉。
     * 刚换维度、新世界的钟还没同步过来时游戏时间会短暂读成 0，这时什么都不到点，等钟同步回来再发。
     *
     * @param now      此刻的游戏时间（刻）
     * @param timeText 此刻的昼夜说法，和 observe 里的 time 一样
     */
    void fireDue(long now, String timeText) {
        for (Timer timer : pending()) {
            if (timer.dueTick() > now) break;
            pending.remove(timer);
            events.publish(TaskEvent.Kind.TIMER_DUE, "计时 " + timer.id() + " 到点：" + timer.note()
                    + "（" + timer.afterSeconds() + " 秒前设的；此刻" + timeText + "）");
        }
    }

    /** 换世界、断线：没到点的全部作废，不跟进下一个世界。 */
    public void clear() {
        pending.clear();
    }
}
