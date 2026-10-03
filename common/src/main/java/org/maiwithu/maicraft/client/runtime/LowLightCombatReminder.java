// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 将附近的真实敌怪命中与当前低光关联；只给生活常识建议，不推断刷怪原因，也不接管身体。 */
public final class LowLightCombatReminder {
    public static final String ID = "repeated_attacks_in_low_light";
    private static final long WINDOW_TICKS = 60 * 20;
    private static final int MIN_HITS = 3, LOW_LIGHT_BELOW = 8, RADIUS = 16;
    public record Observation(long tick, String dimension, BlockPos position,
                              int blockLight, int skyLight, int localLight) {}
    private record Hit(long tick, BlockPos position, String type) {}
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    private final ReminderBoard board;
    private Observation previous;

    public LowLightCombatReminder(ReminderBoard board) { this.board = board; }

    /** 每刻复核范围、时间和亮度；只有本次收到敌怪伤害包时才传入攻击者类型并累计一次命中。 */
    public void observe(Observation now, String hostileType) {
        if (previous != null && (!previous.dimension().equals(now.dimension()) || now.tick() < previous.tick())) {
            hits.clear();
            board.remove(ID, "observation_context_changed", now.tick());
        }
        previous = now;
        // 跑离受袭地点就不再沿用那里的危险；旧记录自然过期，单次战斗不会永久要求角色插火把。
        hits.removeIf(hit -> now.tick() - hit.tick() >= WINDOW_TICKS
                || hit.position().distSqr(now.position()) > RADIUS * RADIUS);
        if (now.localLight() < 0 || now.blockLight() < 0 || now.skyLight() < 0) {
            board.remove(ID, "light_observation_unavailable", now.tick());
            return;
        }
        if (now.localLight() >= LOW_LIGHT_BELOW) {
            hits.clear();
            board.remove(ID, "local_light_recovered", now.tick());
            return;
        }
        if (hostileType != null) hits.addLast(new Hit(now.tick(), now.position().immutable(), hostileType));
        if (hits.size() < MIN_HITS) {
            board.remove(ID, "recent_nearby_attacks_below_threshold", now.tick());
            return;
        }
        JsonObject evidence = new JsonObject();
        evidence.addProperty("source", "hostile_damage_packets_and_local_light");
        evidence.addProperty("hit_count", hits.size());
        evidence.addProperty("window_ticks", WINDOW_TICKS);
        evidence.addProperty("minimum_hits", MIN_HITS);
        evidence.addProperty("first_hit_tick", hits.getFirst().tick());
        evidence.addProperty("last_hit_tick", hits.getLast().tick());
        evidence.addProperty("nearby_radius_blocks", RADIUS);
        evidence.addProperty("low_light_below", LOW_LIGHT_BELOW);
        // 分开报告方块光、原始天空光与当前有效亮度；夜间的天空光不能冒充角色此刻处于明亮地面。
        evidence.addProperty("block_light", now.blockLight());
        evidence.addProperty("sky_light", now.skyLight());
        evidence.addProperty("local_light", now.localLight());
        evidence.addProperty("light_sample_scope", "player_feet");
        evidence.addProperty("spawn_source_verified", false);
        JsonObject position = new JsonObject();
        position.addProperty("dimension", now.dimension());
        position.addProperty("x", now.position().getX());
        position.addProperty("y", now.position().getY());
        position.addProperty("z", now.position().getZ());
        evidence.add("position", position);
        JsonObject types = new JsonObject();
        for (Hit hit : hits) types.addProperty(hit.type(), types.has(hit.type()) ? types.get(hit.type()).getAsInt() + 1 : 1);
        evidence.add("attacker_type_hits", types);
        JsonArray suggestions = new JsonArray();
        suggestions.add("考虑在反复遇袭的工作区域放置火把，或使用 maicraft:light_area 补光；缺料时先准备光源。");
        suggestions.add("根据当前敌人和任务决定是否先避险或处理威胁；照明不能清除已有怪物，也不保证阻止所有生物生成。");
        board.update(ID, "附近区域在最近 60 秒游戏时间内已确认 " + hits.size()
                + " 次敌对生物攻击，当前脚部有效亮度为 " + now.localLight()
                + "。请考虑补光；尚未确认怪物的生成位置或遇袭原因。", evidence, suggestions, now.tick());
    }

    /** 新身体不得累计上一条命的攻击，也不继承旧现场的亮度结论。 */
    public void clear() { hits.clear(); previous = null; }
}
