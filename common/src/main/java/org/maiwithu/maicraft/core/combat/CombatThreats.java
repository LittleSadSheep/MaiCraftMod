package org.maiwithu.maicraft.core.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;

/** 客户端伤害包提供的自卫证据；读取不会延长记忆，也不会修改服务端 AI 字段。 */
public final class CombatThreats {
    private static final long MEMORY_TICKS = 10L * 20L;
    private record Hit(Mob attacker, long tick) {}
    private static final Map<Integer, Hit> hits = new LinkedHashMap<>();
    private static LocalPlayer body;
    private static ClientLevel level;

    private CombatThreats() {}

    /** 只接受当前本地玩家实际收到的伤害；近旁他人受伤、环境伤害和玩家攻击不授权反击。 */
    public static void damaged(LocalPlayer player, ClientboundDamageEventPacket packet) {
        if (player == null || packet.entityId() != player.getId()) return;
        observe(player);
        if (body == null) return;
        var source = packet.getSource(level);
        Entity cause = source.getEntity();
        // 优先采用伤害包中的射手；箭已经消失时仍然可以识别已加载的射手。
        if (cause == null && source.getDirectEntity() instanceof Projectile projectile) {
            cause = projectile.getOwner();
        }
        if (cause instanceof Mob mob && Menace.hostile(mob) && loadedAlive(mob)) {
            hits.put(mob.getId(), new Hit(mob, level.getGameTime()));
        }
    }

    /** 每个客户端 tick 清理；重生、换世界、断线和死亡都不能继承上一具身体的攻击者。 */
    public static void observe(LocalPlayer player) {
        if (player == null || player.isDeadOrDying()) {
            clear();
            return;
        }
        if (body != player || level != player.clientLevel) {
            clear();
            body = player;
            level = player.clientLevel;
        }
        long now = level.getGameTime();
        hits.values().removeIf(hit -> now < hit.tick() || now - hit.tick() >= MEMORY_TICKS
                || !loadedAlive(hit.attacker()));
    }

    public static void clear() {
        hits.clear();
        body = null;
        level = null;
    }

    public static List<Mob> attackers(LocalPlayer player) {
        observe(player);
        return hits.values().stream().map(Hit::attacker).toList();
    }

    public static boolean recentlyAttackedBy(LocalPlayer player, Mob mob) {
        observe(player);
        Hit hit = hits.get(mob.getId());
        return hit != null && hit.attacker() == mob;
    }

    /** 战场与退路保留附近障碍，同时补入伤害已证实、仍在加载范围内的远程攻击者。 */
    public static List<Mob> around(LocalPlayer player, double radius) {
        Map<Integer, Mob> found = new LinkedHashMap<>();
        Menace.hostilesAround(player, radius).forEach(mob -> found.put(mob.getId(), mob));
        attackers(player).forEach(mob -> found.put(mob.getId(), mob));
        return List.copyOf(found.values());
    }

    private static boolean loadedAlive(Mob mob) {
        return mob.level() == level && mob.isAlive() && !mob.isRemoved()
                && level.getEntity(mob.getId()) == mob;
    }
}
