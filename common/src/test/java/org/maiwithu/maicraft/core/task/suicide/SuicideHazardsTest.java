// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.util.List;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Inventory;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 已加载岩浆与悬崖提供真实候选，随身点火只在没有现成危险时兜底；凝固、积水、未加载和无效参数不能伪造危险或成功。 */
public final class SuicideHazardsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        SuicideTaskTest.lavaTags();
        var defaults = SuicideRequest.parse(new JsonObject());
        check(defaults.method().equals("auto") && defaults.radius() == 24 && !defaults.keepInventoryConfirmed(), "默认不能猜测多人规则");
        for (String method : List.of("fire", "lava_bucket"))
            check(SuicideRequest.parse(JsonParser.parseString("{\"method\":\"" + method + "\"}").getAsJsonObject()).method().equals(method),
                    "随身点火和倒岩浆桶应是可声明的寻死方式");
        for (String invalid : List.of("{\"method\":\"kill\"}", "{\"method\":1}", "{\"method\":null}",
                "{\"search_radius\":3}", "{\"search_radius\":65}", "{\"timeout_seconds\":2.5}",
                "{\"timeout_seconds\":\"120\"}", "{\"keep_inventory_confirmed\":\"true\"}")) {
            try { SuicideRequest.parse(JsonParser.parseString(invalid).getAsJsonObject()); throw new AssertionError(invalid); }
            catch (IllegalArgumentException expected) { }
        }
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 2, 8.5));
            world.set(new BlockPos(8, 1, 8), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(9, 1, 8), Blocks.LAVA.defaultBlockState());
            check(SuicideHazards.standing(world.level, new BlockPos(8, 2, 8)), "岸边脚位应可站立，边界=" + world.level.isInWorldBounds(new BlockPos(8, 2, 8)));
            var lava = new SuicideHazards(world.player, new SuicideRequest("lava", 8, 120, true));
            while (!lava.scan()) { }
            var candidate = lava.choose(world.player, Set.of());
            check(candidate != null && candidate.method().equals("lava"), "岸边岩浆应能用原生移动尝试进入");
            var next = lava.choose(world.player, Set.of(candidate.key()));
            check(next == null || !next.key().equals(candidate.key()), "没有效果的同一危险不能无限重试");
            world.set(candidate.entry().below(), Blocks.OBSIDIAN.defaultBlockState());
            check(!SuicideHazards.valid(world.player, candidate), "岩浆凝固后必须重新选择危险");
            check(world.blockUses() == 0 && world.itemUses() == 0, "找危险不能修改地形或背包");
            // 带着打火石时 auto 仍先选已观察到的岩浆，随身点火只是现成危险都用完后的兜底。
            world.set(candidate.entry().below(), Blocks.LAVA.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            var auto = new SuicideHazards(world.player, new SuicideRequest("auto", 8, 120, true));
            while (!auto.scan()) { }
            check(auto.choose(world.player, Set.of()).method().equals("lava"), "身边有岩浆时 auto 不应先点火");
        }
        try (var world = new InteractionWorldTestHarness()) {
            // 洞里没有岩浆、高处和怪物：没带点火物如实没有候选，带上打火石后 auto 与 fire 都选脚下这一格。
            world.position(new Vec3(8.5, 1, 8.5));
            var fire = new SuicideHazards(world.player, new SuicideRequest("fire", 8, 120, true));
            check(fire.scan() && fire.choose(world.player, Set.of()) == null, "没有点火物不能编造随身火候选");
            world.inventory.setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.FIRE_CHARGE));
            var charged = fire.choose(world.player, Set.of());
            check(charged != null && charged.method().equals("fire") && charged.entry().equals(new BlockPos(8, 1, 8)), "副手火焰弹也能原地点火");
            world.inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
            world.inventory.setItem(3, new ItemStack(Items.FLINT_AND_STEEL));
            var auto = new SuicideHazards(world.player, new SuicideRequest("auto", 8, 120, true));
            while (!auto.scan()) { }
            var own = auto.choose(world.player, Set.of());
            check(own != null && own.method().equals("fire") && own.entry().equals(new BlockPos(8, 1, 8)), "auto 找不到现成危险时应改为原地点火");
            check(fire.choose(world.player, Set.of(SuicideSelfHazard.Kind.FIRE.rejectedKey())) == null, "原生点火被拒绝后不能换格反复点火");
            // 寻死不包含烧房子的授权：蔓延范围里有木板就换到够远的空地。
            world.set(new BlockPos(9, 2, 8), Blocks.OAK_PLANKS.defaultBlockState());
            var moved = fire.choose(world.player, Set.of());
            check(moved != null && (Math.abs(moved.entry().getX() - 9) > 1 || Math.abs(moved.entry().getZ() - 8) > 1
                    || moved.entry().getY() > 3 || moved.entry().getY() < 1), "可燃方块旁边不能点火");
            check(world.blockUses() == 0 && world.itemUses() == 0, "选点火格不能提前使用物品");
        }
        try (var world = new InteractionWorldTestHarness()) {
            // 同时带着岩浆桶和打火石：auto 先倒岩浆；倒桶被原生拒绝后改为点火；岩浆流经范围有木板时不倒，退回点火。
            SuicideTaskTest.dimension(world); world.position(new Vec3(8.5, 1, 8.5));
            world.inventory.setItem(0, new ItemStack(Items.LAVA_BUCKET));
            world.inventory.setItem(1, new ItemStack(Items.FLINT_AND_STEEL));
            var auto = new SuicideHazards(world.player, new SuicideRequest("auto", 8, 120, true));
            while (!auto.scan()) { }
            var lava = auto.choose(world.player, Set.of());
            check(lava != null && lava.method().equals("lava_bucket") && lava.entry().equals(new BlockPos(8, 1, 8)), "没有现成危险时 auto 应先原地倒岩浆");
            var fallback = auto.choose(world.player, Set.of(SuicideSelfHazard.Kind.LAVA_BUCKET.rejectedKey()));
            check(fallback != null && fallback.method().equals("fire"), "倒桶被原生拒绝后应改为点火");
            world.set(new BlockPos(12, 2, 8), Blocks.OAK_PLANKS.defaultBlockState());
            var guarded = auto.choose(world.player, Set.of());
            check(guarded != null && guarded.method().equals("fire") && guarded.entry().equals(new BlockPos(8, 1, 8)),
                    "岩浆流经并可点燃的范围内有木板时不能倒岩浆，火的蔓延范围够不着时仍可点火");
            check(world.blockUses() == 0 && world.itemUses() == 0, "选倒桶格不能提前使用物品");
        }
        try (var world = new InteractionWorldTestHarness()) {
            world.position(new Vec3(8.5, 10, 8.5));
            world.set(new BlockPos(8, 9, 8), Blocks.STONE.defaultBlockState());
            world.set(new BlockPos(9, 0, 8), Blocks.STONE.defaultBlockState());
            var fall = new SuicideHazards(world.player, new SuicideRequest("fall", 12, 120, true));
            while (!fall.scan()) { }
            var candidate = fall.choose(world.player, Set.of());
            check(candidate != null && candidate.method().equals("fall"), "高台旁有实际落地柱时应能尝试坠落");
            world.set(new BlockPos(candidate.entry().getX(), 1, candidate.entry().getZ()), Blocks.WATER.defaultBlockState());
            check(!SuicideHazards.valid(world.player, candidate), "已有水缓冲的柱不能误报为已观察到的摔伤地点");
            check(SuicideHazards.fallHeight(world.level, new BlockPos(16, 10, 8)) == 0, "未加载列不能读方块或伪造悬崖");
        }
        System.out.println("SuicideHazardsTest: passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
