// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.effects.AddValue;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.predicates.DamageSourceCondition;

/**
 * 按当前生命、黄心、摔落相关属性和能理解的附魔，估算某个落差能否活下来。计算前取样，实际离地前仍要复查。
 * 抗性与缓降效果要能持续到估计的落地时刻才计入；模组自定义伤害和不能理解的附魔效果不在完整模拟范围内。
 */
public record FallDamageBudget(float health, float absorption, float safeFallDistance,
        double damageMultiplier, float protection, int resistanceLevel, int resistanceTicks,
        int slowFallingTicks, double gravity, float accumulatedFallDistance, boolean immune) {

    public enum Landing {
        ORDINARY(1, 1), CUSHIONED(1, 0.2F), BED(0.5F, 1);
        final float distanceScale, damageScale;
        Landing(float distanceScale, float damageScale) {
            this.distanceScale = distanceScale;
            this.damageScale = damageScale;
        }
        public static Landing of(BlockState support) {
            if (support.is(Blocks.HAY_BLOCK) || support.is(Blocks.HONEY_BLOCK)) return CUSHIONED;
            // Exact vanilla class only: an unknown mod subclass may override fallOn.
            if (support.getBlock().getClass() == BedBlock.class) return BED;
            return ORDINARY; // In particular, sneaking on slime does not cushion a fall.
        }
    }

    public static FallDamageBudget capture(LocalPlayer player) {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("fall budget must be captured on the client thread");
        }
        DamageSource fall = player.damageSources().fall();
        var resistance = player.getEffect(MobEffects.DAMAGE_RESISTANCE);
        var slow = player.getEffect(MobEffects.SLOW_FALLING);
        boolean effects = !fall.is(DamageTypeTags.BYPASSES_EFFECTS);
        double gravity = player.getAttributeValue(Attributes.GRAVITY);
        if (slow != null) gravity = Math.min(gravity, 0.01);
        // Levitation makes the time to landing unknown; do not rely on finite buffs expiring later.
        if (player.hasEffect(MobEffects.LEVITATION)) gravity = 0;
        return new FallDamageBudget(player.getHealth(), player.getAbsorptionAmount(),
                (float) player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER),
                effects && !fall.is(DamageTypeTags.BYPASSES_ENCHANTMENTS) ? protection(player, fall) : 0,
                resistance != null && effects && !fall.is(DamageTypeTags.BYPASSES_RESISTANCE)
                        ? resistance.getAmplifier() + 1 : 0,
                duration(resistance), duration(slow), gravity,
                player.onGround() ? 0 : player.fallDistance,
                player.getAbilities().mayfly || player.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE));
    }

    // 扣除预计伤害和可吸收部分后，生命必须仍大于零；这只判断能活下来，并不要求完全不受伤。
    public boolean survives(double remainingDistance, Landing landing, boolean includeAccumulated) {
        float damage = damage(remainingDistance, landing, includeAccumulated);
        return Float.isFinite(health) && health > 0 && Float.isFinite(absorption) && absorption >= 0
                && Float.isFinite(damage) && health - Math.max(damage - absorption, 0) > 0;
    }

    public float damage(double remainingDistance, Landing landing, boolean includeAccumulated) {
        if (!Double.isFinite(remainingDistance) || remainingDistance < 0
                || !Float.isFinite(safeFallDistance) || !Double.isFinite(damageMultiplier)
                || damageMultiplier < 0 || !Float.isFinite(accumulatedFallDistance)
                || accumulatedFallDistance < 0 || !Float.isFinite(protection)) return Float.POSITIVE_INFINITY;
        if (immune) return 0;
        double ticks = landingTickBound(remainingDistance);
        if (lasts(slowFallingTicks, ticks)) return 0;
        double distance = remainingDistance + (includeAccumulated ? accumulatedFallDistance : 0);
        // LivingEntity.calculateFallDamage: distance and block scale are floats before the
        // attribute multiplier. Beds halve distance; hay/honey scale damage before ceil.
        float excess = (float) distance * landing.distanceScale - safeFallDistance;
        float damage = (float) Math.max(0, Math.ceil((double) (excess * landing.damageScale) * damageMultiplier));
        if (lasts(resistanceTicks, ticks) && resistanceLevel > 0) {
            damage = Math.max(damage * Math.max(0, 25 - Math.min(resistanceLevel, 5) * 5) / 25.0F, 0);
        }
        return CombatRules.getDamageAfterMagicAbsorb(damage, protection);
    }

    // 用保守的时间上界判断药效是否够长；这不是精确的落地倒计时。
    private double landingTickBound(double distance) {
        // Starting at rest, every downward tick after the first covers at least gravity*0.98.
        // This deliberately loose bound also allows one second to clear the supporting edge.
        return gravity > 0 && Double.isFinite(gravity) ? 22 + Math.ceil(distance / (gravity * 0.98))
                : Double.POSITIVE_INFINITY;
    }

    private static boolean lasts(int ticks, double required) { return ticks == -1 || ticks > required; }
    private static int duration(MobEffectInstance effect) { return effect == null ? 0 : effect.getDuration(); }

    private static float protection(LocalPlayer player, DamageSource fall) {
        float total = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            var enchants = player.getItemBySlot(slot).getEnchantments();
            for (var holder : enchants.keySet()) {
                if (!holder.value().matchingSlot(slot)) continue;
                var components = holder.value().getEffects(EnchantmentEffectComponents.DAMAGE_PROTECTION);
                if (components.isEmpty()) continue;
                // Unknown protection effects could also *lower* prior protection. In that case
                // credit none rather than assume additive reduction from familiar enchant names.
                // 当前先按附魔名称筛选，遇到火焰保护等其他名称会直接把总保护返回零，连已知的摔落保护也丢掉。
                if (!holder.is(Enchantments.PROTECTION) && !holder.is(Enchantments.FEATHER_FALLING)) return 0;
                for (var component : components) {
                    if (!(component.effect() instanceof AddValue add)) return 0;
                    if (component.requirements().isPresent()) {
                        if (!(component.requirements().get() instanceof DamageSourceCondition condition)
                                || condition.predicate().isEmpty()) return 0;
                        var predicate = condition.predicate().get();
                        if (predicate.directEntity().isPresent() || predicate.sourceEntity().isPresent()
                                || predicate.isDirect().isPresent()) return 0;
                        if (predicate.tags().stream().anyMatch(tag -> !tag.matches(fall.typeHolder()))) continue;
                    }
                    total += add.value().calculate(enchants.getLevel(holder));
                }
            }
        }
        return total;
    }
}
