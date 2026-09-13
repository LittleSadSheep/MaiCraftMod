// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import sun.misc.Unsafe;

/** 原生方块外形构成的高柱夹具：验证三十二格上限、真实下层支撑和柱底地面，整个检查没有角色动作。 */
public final class BuildScaffoldDescentBoundsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        exactlyThirtyTwoOwnedBlocksAreBounded();
        aGapOrHalfBlockCannotHideALargerFall();
        isolatedPatchesAndSingleWidthBridgesAreNotExits();
        gravityBlocksAndUnownedColumnsAreRejected();
        System.out.println("BuildScaffoldDescentBoundsTest: passed");
    }

    private static void exactlyThirtyTwoOwnedBlocksAreBounded() throws Exception {
        try (var f = new Fixture(32)) {
            var plan = f.plan();
            check(plan.accepted() && plan.steps().size() == 32 && plan.exitRoute().size() >= 2,
                    "三十二格真实连续自有柱应在有限观察内证明到地面：" + plan.evidence());
            check(((Number) plan.evidence().get("observed_blocks")).intValue() <= 8192, "高柱证明保持原始读取预算");
            for (var step : plan.steps()) check(step.from().subtract(step.landing()).equals(new Vec3(0, 1, 0)),
                    "每一步只下降一格，不把整柱一次拆空");
            f.unchanged();
        }
        try (var f = new Fixture(33)) {
            var plan = f.plan();
            check(!plan.accepted() && plan.reason().equals("descent_column_limit") && plan.steps().size() == 32,
                    "超过三十二格拒绝整次计划，不能只证明前半柱就让角色开始下去");
            f.unchanged();
        }
    }

    private static void aGapOrHalfBlockCannotHideALargerFall() throws Exception {
        try (var f = new Fixture(3)) {
            f.level.states.put(new BlockPos(8, 2, 8), Blocks.AIR.defaultBlockState());
            var plan = f.plan(); check(!plan.accepted() && plan.reason().equals("descent_step_not_supported"), "下层柱断开时第一格也不能拆");
            check(f.level.getBlockState(f.top).is(Blocks.DIRT), "没有提前移除上层柱来尝试落地");
        }
        try (var f = new Fixture(1)) {
            f.level.states.put(new BlockPos(8, 0, 8), Blocks.STONE_SLAB.defaultBlockState());
            check(!f.plan().accepted(), "下半砖会造成实际一点五格落差，不能以方块坐标差一格冒充安全");
        }
        try (var f = new Fixture(1)) {
            f.level.loaded = pos -> pos.getY() >= 1;
            check(!f.plan().accepted(), "下层地面不可读时拒绝，不用未知地板托住角色");
        }
    }

    private static void isolatedPatchesAndSingleWidthBridgesAreNotExits() throws Exception {
        for (int shape = 0; shape < 3; shape++) try (var f = new Fixture(3)) {
            int chosen = shape;
            // 单格孤台、二乘二小台、一格宽长桥，都不能当作拆完整柱后的可靠离场地面。
            f.level.floor = pos -> chosen == 0 ? pos.getX() == 8 && pos.getZ() == 8
                    : chosen == 1 ? pos.getX() >= 8 && pos.getX() <= 9 && pos.getZ() >= 8 && pos.getZ() <= 9 : pos.getZ() == 8;
            var plan = f.plan();
            check(!plan.accepted() && plan.reason().equals("descent_no_connected_exit_ground"),
                    "最终没有连片已有地面时保留整柱：" + plan.evidence());
            f.unchanged();
        }
    }

    private static void gravityBlocksAndUnownedColumnsAreRejected() throws Exception {
        try (var f = new Fixture(2)) {
            f.level.states.put(f.top, Blocks.GRAVEL.defaultBlockState()); f.owned.put(f.top, Blocks.GRAVEL.defaultBlockState());
            check(!f.plan().accepted(), "可受重力影响的自有整块也不能纳入这种单格下降模型");
        }
        try (var f = new Fixture(2)) {
            f.owned.remove(f.top);
            check(!f.plan().accepted() && f.level.getBlockState(f.top).is(Blocks.DIRT), "仅同名泥土不能代替真实自有记录");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness h = new InteractionWorldTestHarness();
        final SceneLevel level;
        final Map<BlockPos, BlockState> owned = new LinkedHashMap<>();
        final BlockPos top;
        Fixture(int height) throws Exception {
            Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null); level = (SceneLevel) memory.allocateInstance(SceneLevel.class);
            level.states = new LinkedHashMap<>(); level.border = new WorldBorder(); level.floor = p -> true;
            level.loaded = p -> p.getX() >= 0 && p.getX() < 16 && p.getZ() >= 0 && p.getZ() < 16;
            field(Level.class, "dimension").set(level, Level.OVERWORLD); field(Level.class, "isClientSide").setBoolean(level, true);
            // 客户端世界字段定义在父类，测试也按原版玩家继承关系安装真实观察视图。
            field(Entity.class, "level").set(h.player, level); field(net.minecraft.client.player.AbstractClientPlayer.class, "clientLevel").set(h.player, level); Minecraft.getInstance().level = level;
            field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
            for (int y = 1; y <= height; y++) { var pos = new BlockPos(8, y, 8); level.states.put(pos, Blocks.DIRT.defaultBlockState()); owned.put(pos, Blocks.DIRT.defaultBlockState()); }
            top = new BlockPos(8, height, 8); h.position(new Vec3(8.5, height + 1, 8.5)); h.player.setDeltaMovement(new Vec3(0, -.0784, 0));
        }
        BuildScaffoldDescent plan() { return BuildScaffoldDescent.inspect(h.player, top, owned, p -> true, LongSets.emptySet(), p -> PhysicalObstacleSnapshot.EMPTY); }
        void unchanged() {
            check(owned.entrySet().stream().allMatch(e -> level.getBlockState(e.getKey()).equals(e.getValue()))
                    && h.player.getY() == top.getY() + 1 && h.blockUses() == 0 && h.itemUses() == 0, "只读计划不得改柱体、身体或原生操作计数");
        }
        public void close() throws Exception { h.close(); }
    }
    private static final class SceneLevel extends ClientLevel {
        Map<BlockPos, BlockState> states; WorldBorder border; Predicate<BlockPos> floor, loaded;
        private SceneLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean isLoaded(BlockPos pos) { return loaded.test(pos); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, (pos.getY() == 0 && floor.test(pos) ? Blocks.STONE : Blocks.AIR).defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public List<Entity> getEntities(Entity source, AABB bounds, Predicate<? super Entity> filter) { return List.of(); }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public long getGameTime() { return 100; }
    }
    private static Field field(Class<?> owner, String name) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
