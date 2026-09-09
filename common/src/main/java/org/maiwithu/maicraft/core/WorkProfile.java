package org.maiwithu.maicraft.core;

import net.minecraft.client.player.LocalPlayer;

/**
 * 给任务一组简化的工作规则：是否按免费材料、会掉落物、有饥饿、无普通伤害风险和瞬间挖掘来处理。
 * 目前只看玩家 instabuild 开关，在预设的生存与创造两组值之间选择，不逐项核对实际能力或 Mod 特殊规则。
 * 每次调用重新读取，没有跨刻缓存；服务器最后是否允许操作，仍由真实动作决定。
 */
public record WorkProfile(
        boolean freeMaterials,   // 放置/使用不消耗物品
        boolean dropsLoot,       // 破坏方块会产生掉落物
        boolean hasHunger,       // 有饥饿机制(需要进食;疾跑受饱食度门限)
        boolean fearless,        // 摔落/溺水等物理伤害免疫
        boolean instaBreak) {    // 挖掘瞬间完成,且无视工具等级

    public static final WorkProfile SURVIVAL = new WorkProfile(false, true, true, false, false);
    public static final WorkProfile CREATIVE = new WorkProfile(true, false, false, true, true);

    public static WorkProfile of(LocalPlayer body) {
        return body.getAbilities().instabuild ? CREATIVE : SURVIVAL;
    }
}
