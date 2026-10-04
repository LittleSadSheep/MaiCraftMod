// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.runtime;

import com.google.gson.JsonArray;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LightLayer;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.ReminderBoard;

/** 游戏线程收集事实并更新规则，网络线程只读提醒板；任何提醒都不会暂停任务或执行角色动作。 */
public final class GameplayReminders {
    private static final ReminderBoard BOARD = new ReminderBoard(event -> IntentRuntime.get()
            .gameEvent("agent.reminder", event.get("message").getAsString(), event));
    private static final LowLightCombatReminder LOW_LIGHT = new LowLightCombatReminder(BOARD);
    private static final FoodSupplyReminder FOOD = new FoodSupplyReminder(BOARD);
    private static final CombatEquipmentReminder EQUIPMENT = new CombatEquipmentReminder(BOARD);
    private static final SleepReminder SLEEP = new SleepReminder(BOARD);
    private static final NativeRestStatistics REST_STATS = new NativeRestStatistics(SLEEP);
    private static LocalPlayer body;
    private static ClientLevel level;
    private static long lastFoodTick = -1;

    private GameplayReminders() {}

    /** 无新攻击时也复核光照与口粮；插灯、天亮、离场或备足食物后撤下对应的旧提醒。 */
    public static void tick(LocalPlayer player) {
        if (!bind(player)) return;
        LOW_LIGHT.observe(sample(player), null);
        // 个人原生统计决定是否长期未睡，当前昼夜决定提醒文案；睡眠变化只撤下自己的那条提醒。
        REST_STATS.tick(player);
        long now = level.getGameTime();
        // 口粮按秒复核即可识别持续不足；伤害与血量仍逐刻观察，不因低频盘点背包错过重击。
        if (lastFoodTick < 0 || now < lastFoodTick || now - lastFoodTick >= 20) {
            FOOD.observe(ReminderInventoryFacts.food(player));
            lastFoodTick = now;
        }
    }

    /** 只在原生伤害包消费处调用；玩家、中立动物和无法确认来源的掉血不据此建议防刷怪补光。 */
    public static void damaged(LocalPlayer player, LivingEntity attacker) {
        if (bind(player) && Menace.hostile(attacker)) LOW_LIGHT.observe(sample(player),
                BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
    }

    /** 整批原生伤害包只配对一次血量采样；其他来源的伤害阻断重伤归因，旧自卫证据仍由原通道保留。 */
    public static void observeCombat(LocalPlayer player, List<CombatThreats.DamageNotice> notices) {
        if (!bind(player)) return;
        int hostiles = 0;
        for (var notice : notices) if (Menace.hostile(notice.attacker())) hostiles++;
        EQUIPMENT.observe(ReminderInventoryFacts.equipment(player), hostiles, hostiles != notices.size());
    }

    /** 接住当前连接实际送达的个人统计，四条规则仍共用按 ID 保存的提醒板。 */
    public static void receiveRestStatistics(LocalPlayer player, ClientboundAwardStatsPacket packet) {
        if (bind(player)) REST_STATS.received(player, packet);
    }

    /** 不访问世界、不推进规则，也不消费证据，供所有工具出口读取同一份最新观察。 */
    public static JsonArray snapshot() { return BOARD.snapshot(); }

    private static boolean bind(LocalPlayer player) {
        // 创造和旁观状态不需要生存补给或战斗准备；进入这些状态也作废此前的生存提醒。
        if (player == null || player.clientLevel == null || player.isDeadOrDying()
                || player.getAbilities().instabuild || player.isSpectator()) {
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
        FOOD.clear();
        EQUIPMENT.clear();
        REST_STATS.clear();
        lastFoodTick = -1;
        body = null;
        level = null;
    }
}
