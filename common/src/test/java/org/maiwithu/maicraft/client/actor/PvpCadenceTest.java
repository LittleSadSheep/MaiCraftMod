package org.maiwithu.maicraft.client.actor;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.combat.PvpCadence;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 使用真实武器攻速和游戏刻，确认回执提前返回与换目标都不会把攻击变成连点。 */
public final class PvpCadenceTest {
    public static void main(String[] args) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            f.h.player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(1.6);
            var cadence = new PvpCadence();
            check(!cadence.ready(f.h.player), "新武器先等属性稳定"); f.h.nextTick();
            check(cadence.ready(f.h.player), "稳定后允许检查原生充能和准星"); cadence.submitted(f.h.player);
            for (int i = 0; i < 11; i++) {
                f.h.nextTick(); check(!cadence.ready(f.h.player), "剑的出刀间隔内不能因确认很快就再点一次");
            }
            f.h.nextTick(); check(cadence.ready(f.h.player), "达到剑的实际间隔后可再次出手");
            // 换成攻速更慢的斧后使用新的间隔，不能沿用剑的节拍。
            f.h.inventory.setItem(0, new ItemStack(Items.WOODEN_AXE));
            f.h.player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(.8);
            check(!cadence.ready(f.h.player), "换斧当刻不挥击"); f.h.nextTick(); cadence.submitted(f.h.player);
            for (int i = 0; i < 23; i++) { f.h.nextTick(); check(!cadence.ready(f.h.player), "斧使用较慢攻击节拍"); }
            f.h.nextTick(); check(cadence.ready(f.h.player), "斧间隔结束后可再次尝试");
        }
        System.out.println("PvpCadenceTest: 武器稳定与剑斧攻击节拍通过");
    }
}
