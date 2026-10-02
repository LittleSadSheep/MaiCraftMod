package org.maiwithu.maicraft.core.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/** 点名玩家 -> 绑定本次任务和玩家身份 -> 对手还击继续交战 -> 结束后撤销许可。 */
public final class PvpEngagement {
    private record Opponent(Player entity, UUID uuid) {}
    private static PvpEngagement active;
    private final LocalPlayer body;
    private final ClientLevel level;
    private final AttackTaskRecord attack;
    private final TaskRecord owner;
    private final Map<Integer, Opponent> opponents = new LinkedHashMap<>();
    private final Map<Integer, Long> incoming = new LinkedHashMap<>();
    private final Map<Integer, Long> hits = new LinkedHashMap<>();
    private boolean closed;

    public PvpEngagement(LocalPlayer body, AttackTaskRecord attack) {
        this.body = body; this.level = body.clientLevel; this.attack = attack;
        owner = CompanionTickDispatcher.current();
        // 只绑定已经通过战斗入口确认的点名玩家；附近玩家、自动自卫和重生后的新实体不能继承许可。
        if (!attack.indiscriminate) for (int id : attack.entityIds) {
            if (level.getEntity(id) instanceof Player other && other != body && other.getUUID() != null)
                opponents.put(id, new Opponent(other, other.getUUID()));
        }
    }

    public void activate() {
        // 普通打怪或临时避险不覆盖原对战；暂停、取消及所属任务变化会使原许可立即失效。
        if (!opponents.isEmpty() && eligible()) active = this;
    }

    public void close() {
        // 清理先撤销对战，再释放武器；即使原生动作清理失败也不残留玩家攻击许可。
        closed = true;
        if (active == this) active = null;
    }

    public static void clear() {
        // 断线或换身体时连同旧战斗关闭，不能在新世界重新激活旧的玩家名单。
        if (active != null) active.close();
    }

    public static boolean accepts(LocalPlayer self, Player other) {
        // 只有自动控制仍持有同一具身体时，已授权对手的还击才免于“玩家请求停工”的处理。
        return active != null && active.body == self && active.matches(other)
                && ClientRuntime.actor().activeContext().filter(c -> c.player() == self && c.permitsNativeActions()).isPresent();
    }

    public static List<Player> opponents(LocalPlayer self) {
        // 每次观察重新验证对象、UUID、死亡及加载状态，实体编号复用和重生均终止旧对战对象。
        if (active == null || active.body != self || !active.eligible()) return List.of();
        return active.opponents.values().stream().map(Opponent::entity).filter(active::matches).toList();
    }

    private boolean eligible() {
        return !closed && !attack.getState().isTerminal() && body.clientLevel == level && body.isAlive()
                && CompanionTickDispatcher.current() == owner
                && (owner == null || !owner.getState().isTerminal())
                && !(owner instanceof IntentTaskRecord intent && intent.paused());
    }

    public static void observeDamage(LocalPlayer self, ClientboundDamageEventPacket packet) {
        // 同一伤害包分清“对手打我”和“我打对手”；远端生命条不可见时仍可用本方伤害事件确认命中。
        PvpEngagement fight = active;
        if (fight == null || self != fight.body || !fight.eligible()) return;
        if (packet.entityId() != self.getId() && !fight.opponents.containsKey(packet.entityId())) return;
        var source = packet.getSource(fight.level);
        Entity cause = source.getEntity();
        if (cause == null && source.getDirectEntity() instanceof Projectile projectile) cause = projectile.getOwner();
        if (packet.entityId() == self.getId() && cause instanceof Player other && fight.matches(other))
            fight.incoming.put(other.getId(), fight.level.getGameTime());
        Opponent victim = fight.opponents.get(packet.entityId());
        if (cause == self && victim != null && fight.level.getEntity(packet.entityId()) == victim.entity()
                && victim.uuid().equals(victim.entity().getUUID())) fight.hits.merge(packet.entityId(), 1L, Long::sum);
    }

    public static boolean recentlyAttackedBy(LocalPlayer self, Player other) {
        // 远处射手刚造成伤害时继续撤离；记忆过期且已拉开距离后，不能因对战名单存在而永远逃跑。
        if (!accepts(self, other)) return false;
        Long tick = active.incoming.get(other.getId());
        long now = self.level().getGameTime();
        return tick != null && now >= tick && now - tick < 200;
    }

    public static long hitRevision(LocalPlayer self, Player other) {
        // 已收到的致命命中证据在尸体移除后仍可结算，但不能用于给复活后的新玩家实体授权。
        if (active == null || active.body != self || !active.eligible()) return 0;
        Opponent bound = active.opponents.get(other.getId());
        return bound != null && bound.entity() == other && bound.uuid().equals(other.getUUID())
                ? active.hits.getOrDefault(other.getId(), 0L) : 0;
    }

    private boolean matches(Player other) {
        Opponent bound = opponents.get(other.getId());
        return eligible() && bound != null && bound.entity() == other && bound.uuid().equals(other.getUUID())
                && !attack.terminal(other.getId()) && other.level() == level && other.isAlive()
                && !other.isRemoved() && !other.isSpectator() && level.getEntity(other.getId()) == other;
    }
}
