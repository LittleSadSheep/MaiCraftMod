package org.maiwithu.maicraft.core.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * 为挑近战武器估一个比较分，不是计算这一击实际会扣多少血。
 * 当前只计算固定攻击力加成和物品自身的额外伤害；不计算附魔、挥动速度、暴击、目标护甲等完整战斗效果。
 * 所以“分数更大”只代表这份简化估算更高，不能保证对每个目标都更合适。
 */
public final class WeaponDamage {

    private WeaponDamage() {}

    /**
     * 排序分。<b>只用于比较</b>,不是这一击的真实伤害(见类注释)。
     *
     * @return 该武器打该目标的相对强弱;弓弩这类攻击力为零的返回 0,它们不走近战这条路
     */
    // 先取武器在主手时增加的固定攻击力，再加物品自身对目标的额外伤害；没有固定加伤就按零分。
    public static double against(Player attacker, Entity target, ItemStack weapon) {
        if (weapon.isEmpty()) {
            return 0.0;
        }
        double base = flatAttackDamage(weapon);
        if (base <= 0.0) {
            return 0.0;   // 不是近战武器:方块、食物、以及伤害在箭上的弓弩
        }
        DamageSource source = attacker.damageSources().playerAttack(attacker);
        return base + weapon.getItem().getAttackDamageBonus(target, (float) base, source);
    }

    /**
     * 物品给主手加的那一档攻击力。只认 {@code ADD_VALUE}:乘法档改的是"已经加完的总额",
     * 脱离基础值单看没有意义,而这里比较的正是各把武器各自的那一档。
     */
    // 只累加主手 ATTACK_DAMAGE 的 ADD_VALUE 项。乘法属性、攻击速度和条件附魔不参与本次排序。
    public static double flatAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        ItemAttributeModifiers modifiers =
                stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double damage = 0.0;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND)
                    && entry.attribute().is(Attributes.ATTACK_DAMAGE)
                    && entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
                damage += entry.modifier().amount();
            }
        }
        return damage;
    }
}
