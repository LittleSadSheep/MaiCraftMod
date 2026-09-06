// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import sun.misc.Unsafe;

/** Native raw-damage oracle plus survival boundaries, expiring buffs and accumulated distance. */
public final class FallDamageBudgetTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var ordinary = FallDamageBudget.Landing.ORDINARY;
        var full = budget(20, 0, 3, 1, 0, 0);
        check(full.survives(6, ordinary, false), "ordinary six-block tree drop must permit damage");
        check(full.survives(22, ordinary, false), "one health point remaining is sufficient");
        check(!full.survives(23, ordinary, false), "an exactly fatal landing must fail");
        check(!budget(3, 0, 3, 1, 0, 0).survives(6, ordinary, false), "lost health invalidates prior landing");
        check(budget(3, 1, 3, 1, 0, 0).survives(6, ordinary, false), "absorption contributes available health");
        check(!budget(1, 2, 3, 1, 0, 0).survives(6, ordinary, false), "absorption cannot save an equal-damage landing");
        check(budget(1, 0, 8, 1, 0, 0).survives(8, ordinary, false), "synced safe-distance attribute");
        check(budget(4, 0, 3, 0.5, 0, 0).survives(9, ordinary, false), "synced damage multiplier");
        var feather = budget(5, 0, 3, 1, 12, 0);
        check(feather.survives(12, ordinary, false), "Feather Falling IV protection is applied after ceil");
        check(!budget(5, 0, 3, 1, 0, 0).survives(12, ordinary, false), "removed boots invalidate landing");
        check(budget(5, 0, 3, 1, 100, 0).damage(13, ordinary, false)
                == budget(5, 0, 3, 1, 20, 0).damage(13, ordinary, false), "protection capped at 20");
        var falling = budget(4, 0, 3, 1, 0, 5);
        check(!falling.survives(2, ordinary, true), "already accumulated five blocks cannot be discarded");
        check(falling.survives(2, ordinary, false), "later grounded edges and fresh jumps do not inherit old distance");
        var resistant = new FallDamageBudget(5, 0, 3, 1, 0, 1, 200, 0, 0.08, 0, false);
        var expiring = new FallDamageBudget(5, 0, 3, 1, 0, 1, 2, 0, 0.08, 0, false);
        check(resistant.survives(9, ordinary, false) && !expiring.survives(9, ordinary, false),
                "resistance is credited only when it outlasts a conservative landing bound");
        var slow = new FallDamageBudget(1, 0, 3, 1, 0, 0, 0, 1000, 0.01, 0, false);
        var endingSlow = new FallDamageBudget(1, 0, 3, 1, 0, 0, 0, 2, 0.01, 0, false);
        check(slow.survives(6, ordinary, false) && !endingSlow.survives(6, ordinary, false), "slow falling expiration");
        check(!full.survives(Double.NaN, ordinary, false) && !full.survives(Double.POSITIVE_INFINITY, ordinary, false),
                "unknown distance is not a survivable landing");
        check(FallDamageBudget.Landing.of(Blocks.SLIME_BLOCK.defaultBlockState()) == ordinary, "no assumed bounce when sneaking");
        check(FallDamageBudget.Landing.of(Blocks.HAY_BLOCK.defaultBlockState()) == FallDamageBudget.Landing.CUSHIONED,
                "hay damage multiplier");
        check(FallDamageBudget.Landing.of(Blocks.RED_BED.defaultBlockState()) == FallDamageBudget.Landing.BED,
                "bed distance reduction is a collision property, with no bed use");
        compareNativeFormula();
        System.out.println("FallDamageBudgetTest: passed");
    }

    private static void compareNativeFormula() throws Exception {
        var memoryField = Unsafe.class.getDeclaredField("theUnsafe"); memoryField.setAccessible(true);
        var nativeFall = (NativeFall) ((Unsafe) memoryField.get(null)).allocateInstance(NativeFall.class);
        for (float safe : new float[]{-1, 3, 5.5F}) {
            for (double multiplier : new double[]{0, 0.2, 1, 1.7}) {
                nativeFall.safe = safe; nativeFall.multiplier = multiplier;
                var budget = budget(20, 0, safe, multiplier, 0, 0);
                for (float distance : new float[]{0, 3, 3.001F, 6, 6.4375F, 22, 23}) {
                    for (var landing : FallDamageBudget.Landing.values()) {
                        float nativeDamage = Math.max(0, nativeFall.raw(distance * landing.distanceScale, landing.damageScale));
                        check(budget.damage(distance, landing, false) == nativeDamage,
                                "prediction differs from native LivingEntity.calculateFallDamage");
                    }
                }
            }
        }
    }

    private static FallDamageBudget budget(float health, float absorption, float safe, double multiplier,
                                           float protection, float accumulated) {
        return new FallDamageBudget(health, absorption, safe, multiplier, protection, 0, 0, 0, 0.08, accumulated, false);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class NativeFall extends LivingEntity {
        float safe; double multiplier;
        private NativeFall() { super(EntityType.ARMOR_STAND, null); }
        public EntityType<?> getType() { return EntityType.ARMOR_STAND; }
        public double getAttributeValue(Holder<Attribute> attribute) {
            return attribute.equals(Attributes.SAFE_FALL_DISTANCE) ? safe : multiplier;
        }
        int raw(float distance, float scale) { return calculateFallDamage(distance, scale); }
        public Iterable<ItemStack> getArmorSlots() { return List.of(); }
        public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
        public void setItemSlot(EquipmentSlot slot, ItemStack stack) { throw new AssertionError("no equipment mutation"); }
        public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
    }
}
