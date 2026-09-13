// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.physics.PhysicalObstacleSnapshot;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import static org.maiwithu.maicraft.core.task.build.BuildScaffoldDescent.Status.*;

/** 原生方块几何加已观察运动/回执回放；只测试调度门，不把样本注入冒充真实客户端拆柱。 */
public final class BuildScaffoldDescentTest {
    private static final BlockPos TOP = new BlockPos(8, 3, 8);
    private static final Vec3 START = new Vec3(8.5, 4, 8.5), GRAVITY = new Vec3(0, -.0784, 0);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        confirmedPrefixRequiresActualLandingAndExit();
        lateConfirmationHasAFiniteWait();
        changedWorldProtectionAndPhysicsRejectTheClick();
        unconfirmedAdvanceAndCancellationNeverBreakAnything();
        System.out.println("BuildScaffoldDescentTest: passed");
    }

    private static void confirmedPrefixRequiresActualLandingAndExit() throws Exception {
        try (var f = new Fixture()) {
            var descent = f.plan();
            check(descent.accepted() && descent.steps().size() == 3 && descent.beforeBreak(),
                    "三格自有整柱与柱底连片地面应有完整证明：" + descent.evidence());
            check(f.h.player.position().equals(START) && f.h.level.getBlockState(TOP).is(Blocks.DIRT), "证明不能提前移动或拆柱");
            f.remove(descent); long tick = 10;
            for (double y : new double[]{3.92, 3.69, 3.31}) {
                f.body(y, false, new Vec3(0, -.3, 0));
                check(descent.observe(false, tick++) == DESCENDING && !descent.advance(), "在空中时不能推进第二格");
            }
            f.body(3, true, GRAVITY);
            for (int i = 0; i < 4; i++) check(descent.observe(false, tick++) == DESCENDING,
                    "已经真实站稳也要等原生拆除确认，空气不能代替回执");
            check(descent.observe(true, tick++) == LANDED && descent.advance(), "迟到确认与站稳同时成立后才推进");
            // 每步都把此前确认空气当真实前缀，剩余柱仍必须保持原状态和独立下层支撑。
            while (descent.status() == READY) {
                check(descent.beforeBreak(), "确认前缀不应让下一格证明误报世界变化：" + descent.evidence());
                double landing = descent.step().landing().y; f.remove(descent);
                f.body(landing + .4, false, new Vec3(0, -.3, 0));
                check(descent.observe(true, tick++) == DESCENDING, "确认先到仍须等待自然落地");
                f.body(landing, true, GRAVITY);
                for (int i = 0; i < 3; i++) descent.observe(false, tick++);
                check(descent.status() == LANDED && descent.advance(), "每一步都需要自己的连续站稳观测");
            }
            check(descent.status() == EXIT_REQUIRED && descent.step() == null, "拆完柱子仍不能冒充已离场");
            check(descent.observe(false, tick++) == EXIT_REQUIRED, "留在原柱底不能完成最后走出步骤");
            f.h.position(descent.exit());
            for (int i = 0; i < 3; i++) descent.observe(false, tick++);
            check(descent.status() == COMPLETE && !descent.beforeBreak(), "到已证明实地站稳后完成，不能再挖任何格");
            f.noActions();
        }
    }

    private static void lateConfirmationHasAFiniteWait() throws Exception {
        try (var f = new Fixture()) {
            var descent = f.plan(); check(descent.beforeBreak(), "等待夹具应能开始第一步"); f.remove(descent); f.body(3, true, GRAVITY);
            for (int i = 0; i < 100; i++) check(descent.observe(false, 1) == DESCENDING, "重复同一刻不能消耗等待预算或伪造站稳");
            for (int tick = 2; tick <= 60; tick++) descent.observe(false, tick);
            check(descent.status() == REJECTED && descent.reason().equals("descent_confirmation_or_landing_timeout")
                            && f.h.level.getBlockState(TOP.below()).is(Blocks.DIRT),
                    "回执永不到达时有限退出，仍保留下层柱体");
            f.noActions();
        }
    }

    private static void changedWorldProtectionAndPhysicsRejectTheClick() throws Exception {
        try (var f = new Fixture()) {
            var descent = f.plan(); f.h.set(TOP.below(), Blocks.STONE.defaultBlockState());
            check(!descent.beforeBreak() && descent.status() == REJECTED, "别人替换下层支撑后不得复用旧证明"); f.noActions();
        }
        try (var f = new Fixture()) {
            var descent = f.plan();
            check(!NavigationSafetyContext.withProtectedArea(List.of(TOP), List.of(), descent::beforeBreak), "临时新增拆除保护必须在出手前生效"); f.noActions();
        }
        try (var f = new Fixture()) {
            var descent = f.plan();
            check(!NavigationSafetyContext.withForbiddenBodyCells(List.of(TOP), descent::beforeBreak), "下落路径中新加的身体禁入格不能穿过"); f.noActions();
        }
        try (var f = new Fixture()) {
            var descent = f.plan();
            f.physics.set(new PhysicalObstacleSnapshot(List.of(new AABB(8.1, 3.1, 8.1, 8.9, 3.7, 8.9)), 0, 0, "ready"));
            check(!descent.beforeBreak(), "当前脚位下方新进入的移动结构必须阻止拆除，即使上下端点看起来能站"); f.noActions();
        }
        try (var f = new Fixture()) {
            var descent = f.plan(); check(descent.beforeBreak(), "漂移夹具应先得到证明"); f.remove(descent);
            f.h.position(new Vec3(8.65, 3.7, 8.5)); field(Entity.class, "onGround").setBoolean(f.h.player, false);
            check(descent.observe(true, 1) == REJECTED, "横向离开已证明下降柱时立即停止后续拆除"); f.noActions();
        }
    }

    private static void unconfirmedAdvanceAndCancellationNeverBreakAnything() throws Exception {
        try (var f = new Fixture()) {
            var descent = f.plan(); check(!descent.advance() && descent.beforeBreak(), "尚未拆除时不能跳过当前柱块");
            check(descent.observe(true, 1) == REJECTED, "只有确认标记却没有实际空气不能通过"); f.noActions();
        }
        try (var f = new Fixture()) {
            var descent = f.plan(); descent.cancel();
            check(!descent.beforeBreak() && descent.observe(false, 1) == REJECTED
                    && f.h.level.getBlockState(TOP).is(Blocks.DIRT), "取消只关闭证明状态，不自行挖掘或移动身体"); f.noActions();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final InteractionWorldTestHarness h = new InteractionWorldTestHarness();
        final Map<BlockPos, BlockState> owned = new LinkedHashMap<>();
        final AtomicReference<PhysicalObstacleSnapshot> physics = new AtomicReference<>(PhysicalObstacleSnapshot.EMPTY);
        Fixture() throws Exception {
            field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
            for (int y = 1; y <= 3; y++) { var pos = new BlockPos(8, y, 8); h.set(pos, Blocks.DIRT.defaultBlockState()); owned.put(pos, Blocks.DIRT.defaultBlockState()); }
            body(4, true, GRAVITY); h.inventory.setItem(0, new ItemStack(Items.DIRT, 16));
        }
        BuildScaffoldDescent plan() { return BuildScaffoldDescent.inspect(h.player, TOP, owned, p -> true, LongSets.emptySet(), p -> physics.get()); }
        void remove(BuildScaffoldDescent descent) { h.set(descent.step().block(), Blocks.AIR.defaultBlockState()); }
        void body(double y, boolean grounded, Vec3 velocity) throws Exception {
            h.position(new Vec3(8.5, y, 8.5)); h.player.setDeltaMovement(velocity); field(Entity.class, "onGround").setBoolean(h.player, grounded);
        }
        void noActions() { check(h.blockUses() == 0 && h.itemUses() == 0 && h.inventory.getItem(0).getCount() == 16, "模块不得发原生操作或更改背包"); }
        public void close() throws Exception { h.close(); }
    }
    private static Field field(Class<?> owner, String name) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
