package org.maiwithu.maicraft.core.combat;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/** 原生充能之外再约束本次交战的出刀间隔，避免回执提前完成或切换目标造成连点。 */
public final class PvpCadence {
    private Item weapon;
    private int slot = -1;
    private long changedAt = Long.MIN_VALUE;
    private long nextSwing = Long.MIN_VALUE;

    public boolean ready(Player self) {
        // 换手后先等一个游戏刻，让新武器属性与选中槽稳定；耐久变化不算换武器。
        long tick = self.level().getGameTime();
        Item held = self.getMainHandItem().getItem();
        if (held != weapon || slot != self.getInventory().selected) {
            weapon = held; slot = self.getInventory().selected; changedAt = tick;
            return false;
        }
        return tick > changedAt && tick >= nextSwing;
    }

    public void submitted(Player self) {
        // 从真正交给原生攻击端口的那一刻计时；剑与斧分别遵守其攻击速度，而不是统一每秒点击次数。
        double speed = self.getAttributeValue(Attributes.ATTACK_SPEED);
        double usableSpeed = Double.isFinite(speed) ? Math.max(.1, speed) : .1;
        long interval = Math.max(1, (long) Math.ceil(20.0 / usableSpeed * Swing.ATTACK_READY));
        nextSwing = self.level().getGameTime() + interval;
    }
}
