// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 游戏线程收集事实并更新规则，网络线程只读提醒板；任何提醒都不会暂停任务或执行角色动作。 */
public final class GameplayReminders {
    private static final ReminderBoard BOARD = new ReminderBoard(event -> IntentRuntime.get()
            .gameEvent("agent.reminder", event.get("message").getAsString(), event));
    private static final LowLightCombatReminder LOW_LIGHT = new LowLightCombatReminder(BOARD);
    private static LocalPlayer body;
    private static ClientLevel level;

    private GameplayReminders() {}

    /** 无新攻击时也复核光照和到期时间；插灯、天亮或离场后不再持续提醒旧问题。 */
    public static void tick(LocalPlayer player) {
        if (bind(player)) LOW_LIGHT.observe(sample(player), null);
    }

    /** 只在原生伤害包消费处调用；玩家、中立动物和无法确认来源的掉血不据此建议防刷怪补光。 */
    public static void damaged(LocalPlayer player, LivingEntity attacker) {
        if (bind(player) && Menace.hostile(attacker)) LOW_LIGHT.observe(sample(player),
                BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
    }

    /** 不访问世界、不推进规则，也不消费证据，供所有工具出口读取同一份最新观察。 */
    public static JsonArray snapshot() { return BOARD.snapshot(); }

    private static boolean bind(LocalPlayer player) {
        if (player == null || player.clientLevel == null || player.isDeadOrDying()) {
            reset();
            return false;
        }
        if (body != player || level != player.clientLevel) {
            reset();
            body = player;
            level = player.clientLevel;
        }
        return true;
    }

    private static LowLightCombatReminder.Observation sample(LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        int block = -1, sky = -1, local = -1;
        // 仅读取玩家脚下已加载格的原生照度；缺失的光照数据保持未知，不能用零制造“黑暗”证据。
        if (level.isLoaded(feet)) try {
            block = level.getBrightness(LightLayer.BLOCK, feet);
            sky = level.getBrightness(LightLayer.SKY, feet);
            local = level.getMaxLocalRawBrightness(feet);
        } catch (RuntimeException unavailable) {
            block = sky = local = -1;
        }
        return new LowLightCombatReminder.Observation(level.getGameTime(),
                level.dimension().location().toString(), feet, block, sky, local);
    }

    /** 断线、死亡、重生和换世界都清掉旧身体的提醒；规则的历史不会写入长期任务检查点。 */
    public static void reset() {
        BOARD.clear();
        LOW_LIGHT.clear();
        body = null;
        level = null;
    }
}
