package org.maiwithu.maicraft.core.combat;

import net.minecraft.client.player.LocalPlayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 分别挑一把近战武器和一把远程武器，供战斗任务按距离选择。
 * 近战按 WeaponDamage 的简化分数挑，弓弩按是否已装填和固定优先顺序挑；不会把物品直接换到手里。
 */
public final class Loadout {

    /** 一把候选武器及其背包编号；真正拿到手里还要经过 FirstPersonActionGate。 */
    public record Pick(int slot, ItemStack stack, double score) {}

    private final Pick melee;
    private final Pick ranged;
    private final boolean rangedCharged;

    private Loadout(Pick melee, Pick ranged, boolean rangedCharged) {
        this.melee = melee;
        this.ranged = ranged;
        this.rangedCharged = rangedCharged;
    }

    /** 按简化伤害分选出的近战武器，没有候选时为 null。 */
    public Pick melee() {
        return melee;
    }

    /** 有弹药或已经装填的弓弩候选；不保证它现在就在能直接使用的栏位。 */
    public Pick ranged() {
        return ranged;
    }

    public boolean hasMelee() {
        return melee != null;
    }

    public boolean hasRanged() {
        return ranged != null;
    }

    /** 选中的远程武器是把已上弦的弩——它不必再拉一次,可以立刻射。 */
    public boolean rangedCharged() {
        return rangedCharged;
    }

    /**
     * 扫一遍背包。近战按 {@link WeaponDamage#against} 对<b>这个目标</b>排序;远程按
     * "已上弦的弩 &gt; 弓 &gt; 未上弦的弩"挑——上弦的弩省掉整个拉弓周期,
     * 而弓的蓄力比给弩上弦快。
     */
    public static Loadout forTarget(LocalPlayer player, Entity target) {
        Inventory inventory = player.getInventory();
        Pick bestMelee = null;
        Pick chargedCrossbow = null;
        Pick bow = null;
        Pick loadableCrossbow = null;

        // 这里扫描整个 Inventory，包括副手；后面的选择器只接受前 36 格，范围不一致，见 A34。
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof CrossbowItem) {
                if (CrossbowItem.isCharged(stack)) {
                    if (chargedCrossbow == null) {
                        chargedCrossbow = new Pick(slot, stack, 0.0);
                    }
                } else if (loadableCrossbow == null && !player.getProjectile(stack).isEmpty()) {
                    loadableCrossbow = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            if (stack.getItem() instanceof BowItem) {
                if (bow == null && !player.getProjectile(stack).isEmpty()) {
                    bow = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            double score = WeaponDamage.against(player, target, stack);
            if (score > 0.0 && (bestMelee == null || score > bestMelee.score())) {
                bestMelee = new Pick(slot, stack, score);
            }
        }

        // 固定优先顺序：已装填弩、有箭的弓、可装填弩。同类取先找到的，没有再比较附魔或耐久。
        Pick ranged = chargedCrossbow != null ? chargedCrossbow
                : bow != null ? bow
                : loadableCrossbow;
        return new Loadout(bestMelee, ranged, ranged != null && ranged == chargedCrossbow);
    }
}
