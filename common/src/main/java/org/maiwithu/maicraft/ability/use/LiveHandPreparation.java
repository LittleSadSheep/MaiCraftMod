// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.HotbarSelection;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 手上准备的生产实现：把要用的东西分刻换到主手，像真人一样先做界面搬运再选中快捷栏。
 *
 * <p>标签（# 开头）认身上任意一件挂着这个标签的东西，主手上已经是就不换。
 * 换东西交给换手读端（背包界面与副手交换的原生操作），它分刻推进、每步等游戏确认；
 * 空手就选中一个空着的快捷栏格。身上没有就如实说没有，去拿是任务找拿到物品引擎的事。
 */
final class LiveHandPreparation implements UseSeams.PreparesHand {

    /** 背包里盔甲格的格号：穿在身上的不是拿在手里的东西。 */
    private static final int FIRST_ARMOR_SLOT = 36;
    private static final int LAST_ARMOR_SLOT = 39;

    private final ClientMovesToMainhand toMainhand;
    private final Supplier<PlayerContext> context;

    LiveHandPreparation(ClientMovesToMainhand toMainhand, Supplier<PlayerContext> context) {
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public UseSeams.HandPlan hold(String item) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.WRONG_TIME,
                    "这一刻还掌握不到角色，备不了手", null));
        }
        LocalPlayer player = current.localPlayer();
        if (item == null) {
            return emptyHand(player);
        }
        if (matches(player.getMainHandItem(), item)) {
            return new UseSeams.HandPlan.Ready();
        }
        Optional<String> carried = carriedOutsideArmor(player.getInventory(), item);
        if (carried.isEmpty()) {
            return wornOnly(player.getInventory(), item)
                    ? new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.NEED_ITEM,
                            item + " 穿在身上，不是拿在手里的东西", "先脱下来或另找一件"))
                    : new UseSeams.HandPlan.NotCarried();
        }
        return toMainhand.actionToMainhand(carried.get())
                .<UseSeams.HandPlan>map(UseSeams.HandPlan.Move::new)
                .orElseGet(() -> new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.STUCK,
                        carried.get() + " 在身上却换不到主手", null)));
    }

    // 要空手：主手已空就成了；不然选一个空快捷栏格切过去，一格都没有按腾不出交代。
    private static UseSeams.HandPlan emptyHand(LocalPlayer player) {
        if (player.getMainHandItem().isEmpty()) {
            return new UseSeams.HandPlan.Ready();
        }
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                // 像真人滚一下滚轮切到空着的那一格：经原生选中，等服务端确认。
                return new UseSeams.HandPlan.Move(new HotbarSelection(slot));
            }
        }
        return new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.NEED_ITEM,
                "腾不出空手：快捷栏没有空格", "先丢掉或收起一件东西再试"));
    }

    // 身上（主背包、快捷栏、副手）第一件对得上的东西的物品 ID；盔甲格不算。
    private static Optional<String> carriedOutsideArmor(Inventory inventory, String item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot >= FIRST_ARMOR_SLOT && slot <= LAST_ARMOR_SLOT) continue;
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, item)) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    private static boolean wornOnly(Inventory inventory, String item) {
        for (int slot = FIRST_ARMOR_SLOT; slot <= LAST_ARMOR_SLOT; slot++) {
            if (matches(inventory.getItem(slot), item)) return true;
        }
        return false;
    }

    // 物品 ID 按注册名比；标签按物品自己挂没挂这个标签比，模组加进标签的物品也认。
    private static boolean matches(ItemStack stack, String item) {
        if (stack.isEmpty()) return false;
        String spec = item.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            return tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId));
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(spec);
    }
}
