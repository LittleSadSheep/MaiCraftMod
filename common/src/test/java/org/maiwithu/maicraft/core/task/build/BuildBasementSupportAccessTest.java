// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;

/** 原地下室整体平移到测试区块：x 加十、y 减五十九、z 加七；保留楼梯阶高和天花洞口。 */
public final class BuildBasementSupportAccessTest {
    private static final BlockPos SUPPORT = at(-5, 63, 0), TARGET = at(-5, 64, 0);
    private static final Vec3 ORIGIN = new Vec3(-4.49919785 + 10, 63 - 59, -1.507613 + 7);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        revisedOpeningRestoresStandingAccess();
        try (var h = basement()) {
            var target = target();
            check(h.level.getBlockState(at(-5, 59, 1)).is(Blocks.STONE_BRICKS)
                    && h.level.getBlockState(SUPPORT).isAir() && h.level.getBlockState(TARGET).isAir(),
                    "真实地下室地面和待放支撑/楼梯状态必须与停机现场一致");
            var proof = new BuildSupportAccess(h.player, target, List.of(SUPPORT), Blocks.DIRT.defaultBlockState(),
                    SUPPORT::equals, LongSets.emptySet(), PhysicalObstacleSnapshot.EMPTY);
            for (int tick = 0; tick < 4096 && !proof.advance(16); tick++) {
                if (tick == 4095) throw new AssertionError("地下室支撑完整前缀搜索未在有限预算内结束");
            }
            check(proof.accepted(), "应通过真实楼梯回到地下室低地再放支撑，完整前缀失败证据：" + proof.evidence());
            var support = proof.placementFor(SUPPORT);
            check(support != null && support.feet().y <= 1.5,
                    "支撑放置见证须实际下降到原 Y60 地面，不能在原 Y61 下限处伪造可达");
            check(proof.targetPlacement() != null && proof.targetPlacement().feet().y >= 3.5
                    && proof.witness().clicked().equals(SUPPORT), "最终须返回较高可达阶面，对已证明的支撑放置南向楼梯");
            var projected = new BuildSupportWorld(h.level, h.level::isLoaded, Map.of(SUPPORT, Blocks.DIRT.defaultBlockState()));
            check(BuildPlacementGeometry.projectedGestureFrom(h.player, target, projected, h.level::isLoaded,
                    proof.targetPlacement().feet()) != null, "最终姿态必须通过原生楼梯朝向和点击几何，不能只检查空格邻接");
            check(((Number) proof.evidence().get("reachable_stances")).intValue() <= 512
                    && ((Number) proof.evidence().get("observed_blocks")).intValue() <= 8192,
                    "加深下降范围仍保留落脚点和世界读取上限");
            check(h.level.getBlockState(SUPPORT).isAir() && h.level.getBlockState(TARGET).isAir()
                    && h.inventory.getItem(0).getCount() == 64 && h.blockUses() == 0 && h.itemUses() == 0,
                    "整条支撑验证只读世界，不提前放泥土、不消耗物品、不发送原生动作");

            // 原 Y65 天花仍在，仅楼梯洞口放行；封洞后旧证明必须失效，不能假装身体穿过低天花。
            h.set(at(-5, 65, 0), Blocks.POLISHED_ANDESITE.defaultBlockState());
            check(!proof.environmentCurrent(), "已证明路径的真实洞口封闭后不能继续沿旧见证施工");
        }
        System.out.println("BuildBasementSupportAccessTest: passed");
    }

    private static InteractionWorldTestHarness basement() throws Exception {
        var h = new InteractionWorldTestHarness();
        var dimensions = Entity.class.getDeclaredField("dimensions"); dimensions.setAccessible(true);
        dimensions.set(h.player, EntityDimensions.scalable(.6F, 1.8F)); h.player.setDeltaMovement(Vec3.ZERO);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.set(new BlockPos(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState());
            if (!((x == 4 || x == 5) && (z == 6 || z == 7)))
                h.set(new BlockPos(x, 6, z), Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        var stair = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        for (int x : List.of(-6, -5)) {
            for (int step = 0; step < 4; step++) h.set(at(x, 60 + step, -4 + step), stair);
            // 停机 NBT 确认最高一级也是 half=bottom；“topstairs”表示最高一级，不是倒置楼梯。
            h.set(at(x, 65, 1), stair);
        }
        for (BlockPos dirt : List.of(at(-6, 60, -3), at(-5, 61, -2), at(-6, 62, -1))) h.set(dirt, Blocks.DIRT.defaultBlockState());
        h.position(ORIGIN); h.inventory.setItem(0, new ItemStack(Items.DIRT, 64)); h.player.inventoryMenu.setCarried(ItemStack.EMPTY);
        return h;
    }
    private static void revisedOpeningRestoresStandingAccess() throws Exception {
        try (var h=basement()) {
            var walking=new BuildSupportWalking(h.level,h.level::isLoaded,.6,1.8,LongSets.emptySet(),PhysicalObstacleSnapshot.EMPTY);
            Vec3 upper=new Vec3(-4.5+10,64-59,-.5+7);
            check(!walking.edge(ORIGIN,upper),"原洞口会让正常身体在升到较高阶面时撞上后方天花");
            // 与公开场景修订完全一致：只向北多开两格，不挪动楼梯，不把角色缩小成能穿过旧洞口。
            h.set(at(-6,65,-2),Blocks.AIR.defaultBlockState());h.set(at(-5,65,-2),Blocks.AIR.defaultBlockState());
            check(walking.edge(ORIGIN,upper),"修订后的两格净空允许同一正常身体经过完整升阶扫掠");
            check(h.player.position().equals(ORIGIN) && h.blockUses()==0 && h.itemUses()==0,"净空回归只证明几何，不冒充真实挖掘或身体移动");
        }
    }
    private static BuildTaskRecord.Target target() {
        var desired = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        return new BuildTaskRecord.Target(desired, Items.SPRUCE_STAIRS, TARGET, "地下室续接楼梯", Direction.SOUTH, null, false);
    }
    private static BlockPos at(int x, int y, int z) { return new BlockPos(x + 10, y - 59, z + 7); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
