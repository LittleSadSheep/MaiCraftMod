// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 回去捡尸体：死了身上的东西撒在死的地方，五分钟就消失。真人一重生就往回跑，不等想清楚下一步干什么。
 *
 * <p>每刻记下活着时的样子（在哪、哪个维度、身上有几件）；重生后（换了一个身体、上一个身体是死的）
 * 身上的东西比死前少了，就记下这次死亡。离消失还有时间、同一个维度、离得不太远时尽快插进来：
 * 走到死的地方，把附近地上的东西捡起来，再回去干活。一次死亡只回去一次，捡不全也不再回头。
 */
public final class LostItemsNeed implements SurvivalNeed {

    /** 一刻的身体：活着时每刻读一遍；不在世界里为 null。identity 是那个身体本身，重生会换一个。 */
    public record PlayerNow(Object identity, double x, double y, double z, String dimension,
                       long gameTick, int carriedStacks) {}

    /** 死在哪、什么时候、死前身上几格东西。 */
    public record Death(double x, double y, double z, String dimension, long gameTick, int carriedStacks) {}

    /** 读身体的接缝：生产实现读当刻的本地玩家，测试给固定值。 */
    public interface ReadsPlayer {
        PlayerNow read(TickContext context);

        /** 那个（已经换下来的）身体是不是死了的：死着时循环停摆不来问，重生后回头看一眼旧身体。 */
        boolean died(Object identity);
    }

    /** 回去捡的动作：走到死的地方、捡附近地上的东西。生产实现接走到与共用的捡掉落物。 */
    @FunctionalInterface
    public interface RecoversDrops {
        Task recover(Death death, long expiresAtTick);
    }

    /** 掉落物在地上待满五分钟（6000 刻）就消失；留十秒余量，到不了就别白跑。 */
    static final long DESPAWN_TICKS = 6000 - 200;
    /**
     * 离死的地方这么远（格）以内才回去：疾跑一秒五六格，四百格一分多钟，五分钟里来得及；
     * 再远（重生在千把格外的床边）跑回去东西多半没了。来不来得及另按剩下的时间判断。
     */
    static final double REACH_BLOCKS = 400;

    private final ReadsPlayer players;
    private final RecoversDrops recovery;
    private final TaskEventSink events;
    /** 上一刻看到的身体：死的那一刻循环停摆不再问，所以它就是死前最后一刻的样子。 */
    private PlayerNow last;
    /** 还没回去捡的那次死亡。 */
    private Death pending;
    /** 看到重生的那一刻（游戏刻）。 */
    private long respawnedAt;
    /** 重生后先等这么久（刻）再动身。 */
    static final long SETTLE_TICKS = 40;

    public LostItemsNeed(ReadsPlayer players, RecoversDrops recovery, TaskEventSink events) {
        this.players = Objects.requireNonNull(players, "players");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.events = events;
    }

    @Override public String name() { return "回去捡东西"; }

    @Override
    public Urgency urgency(TickContext context) {
        PlayerNow now = players.read(context);
        if (now == null) return null;
        if (last != null && last.identity() != now.identity() && players.died(last.identity())
                && last.carriedStacks() > now.carriedStacks()) {
            // 换了个身体、旧的是死的、身上比死前少了：刚重生，东西撒在死的地方。
            pending = new Death(last.x(), last.y(), last.z(), last.dimension(), last.gameTick(), last.carriedStacks());
            respawnedAt = now.gameTick();
        }
        last = now;
        if (pending == null) return null;
        // 刚重生那一两秒世界还没就绪（区块、身体、走到都在换新），稍等再动身。
        if (now.gameTick() < respawnedAt + SETTLE_TICKS) return null;
        if (now.gameTick() - pending.gameTick() >= DESPAWN_TICKS
                || !Objects.equals(now.dimension(), pending.dimension())
                || Math.hypot(now.x() - pending.x(), now.z() - pending.z()) > REACH_BLOCKS) {
            // 来不及了、不在一个维度、离得太远：这次不回去了。
            pending = null;
            return null;
        }
        return Urgency.SOON;
    }

    @Override
    public Task createTask(TickContext context) {
        Death death = pending;
        // 一次死亡只回去一次：插进去了就算处理过，捡不全也不再回头。
        pending = null;
        if (events != null) {
            events.publish(TaskEvent.Kind.TEMPORARY_TASK_STARTED,
                    "刚重生，回死的地方捡东西（还有 " + Math.max(0, (death.gameTick() + DESPAWN_TICKS
                            - context.gameTick()) / 20) + " 秒就消失）");
        }
        return recovery.recover(death, death.gameTick() + DESPAWN_TICKS);
    }

    /** 捡不全就算了，别缓着再插：这次死亡已经回去过了。 */
    @Override
    public boolean waitsAfterFailure() {
        return false;
    }
}
