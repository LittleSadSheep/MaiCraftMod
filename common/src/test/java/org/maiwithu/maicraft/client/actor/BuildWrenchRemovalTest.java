// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.task.build.BuildWrenchRemoval;

/** 拆卸动作使用真实 NativeActionPort；无头夹具显式提供服务器方块、ACK 与库存同步，不能拿预测消失冒充完成。 */
public final class BuildWrenchRemovalTest {
    private static final BlockPos AT = new BlockPos(0, 1, 0);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        confirmedRemovalWaitsForAckAndInventory(); uncertainUseIsNeverRepeated();
        check(BuildWrenchRemoval.defaultRemoval(DefaultBlock.class, WrenchContract.class), "inherited native default is admitted");
        check(!BuildWrenchRemoval.defaultRemoval(StrippingBlock.class, WrenchContract.class), "shell-stripping overrides are not called whole-block removal");
        System.out.println("BuildWrenchRemovalTest: passed");
    }
    private static void confirmedRemovalWaitsForAckAndInventory() throws Exception {
        try (var h = fixture()) {
            var permitted = new AtomicBoolean(true);
            var removal = new BuildWrenchRemoval(h.player, AT, Items.STICK, permitted::get);
            h.mode.beforeBlockUse = () -> check(h.player.isShiftKeyDown() && h.player.getPose() == Pose.CROUCHING
                    && h.player.getMainHandItem().is(Items.STICK), "the one native block use has the requested hand and actual crouch input");
            check(removal.tick() == BuildWrenchRemoval.Status.RUNNING && h.blockUses() == 0,
                    "the initial standing posture cannot submit a wrench use");
            next(h); submit(h, removal);
            h.set(AT, Blocks.AIR.defaultBlockState());
            for (int i = 0; i < 4; i++) {
                next(h); check(removal.tick() == BuildWrenchRemoval.Status.RUNNING, "predicted air without the matching ACK remains pending");
            }
            h.level.acknowledgedSequence = h.level.blockSequence; next(h);
            check(removal.tick() == BuildWrenchRemoval.Status.RUNNING, "block removal acknowledgement still waits briefly for the delayed refund");
            h.inventory.setItem(1, new ItemStack(Items.BRICKS)); next(h);
            check(removal.tick() == BuildWrenchRemoval.Status.REMOVED && h.blockUses() == 1 && h.itemUses() == 0
                    && removal.evidence().get("inventory_gain_observed").equals(1),
                    "a single crouch use finishes with separately observed block and item facts");
            removal.stop();
        }
    }
    private static void uncertainUseIsNeverRepeated() throws Exception {
        try (var h = fixture()) {
            var removal = new BuildWrenchRemoval(h.player, AT, Items.STICK, () -> true); submit(h, removal);
            h.level.acknowledgedSequence = h.level.blockSequence;
            BuildWrenchRemoval.Status status = BuildWrenchRemoval.Status.RUNNING;
            for (int i = 0; i < 80 && status == BuildWrenchRemoval.Status.RUNNING; i++) { next(h); status = removal.tick(); }
            check(status == BuildWrenchRemoval.Status.FAILED && removal.uncertain() && h.blockUses() == 1
                    && h.itemUses() == 0 && h.level.getBlockState(AT).is(Blocks.BRICKS),
                    "unconfirmed removal is reported without a second use, item fallback or fabricated air");
            removal.stop();
        }
        try (var h = fixture()) {
            var denied = new BuildWrenchRemoval(h.player, AT, Items.STICK, () -> false);
            check(denied.tick() == BuildWrenchRemoval.Status.FAILED && h.blockUses() == 0,
                    "changed modification permission stops before the native click");
            denied.stop();
        }
    }
    private static InteractionWorldTestHarness fixture() throws Exception {
        var h = new InteractionWorldTestHarness(); h.set(AT, Blocks.BRICKS.defaultBlockState());
        // 原生潜行切换会重算碰撞箱，夹具必须先拥有正常玩家尺寸，不能依赖未初始化的 Unsafe 字段。
        ActorControlTestHarness.field(Entity.class, "dimensions").set(h.player, EntityDimensions.scalable(.6F, 1.8F));
        h.inventory.setItem(0, new ItemStack(Items.STICK)); return h;
    }
    private static void submit(InteractionWorldTestHarness h, BuildWrenchRemoval removal) throws Exception {
        for (int i = 0; i < 80 && h.blockUses() == 0; i++) {
            check(removal.tick() == BuildWrenchRemoval.Status.RUNNING, "the fixture must reach native submission: " + removal.evidence()); next(h);
        }
        check(h.blockUses() == 1, "exactly one native use was submitted: " + removal.evidence());
    }
    private static void next(InteractionWorldTestHarness h) throws Exception {
        // 相机按渲染实时时间平滑转动；无头快速循环同时推进一游戏刻的动画时间，不直接把视角设成目标角度。
        ActorControlTestHarness.field(DefaultBodyControlPort.class, "lastLookUpdateNanos").setLong(h.h.body, System.nanoTime() - 50_000_000L);
        h.h.body.endTick(h.h.context);
        // 无头测试按下一游戏刻兑现输入姿态；生产代码只能等待原生姿态，不设置玩家姿态或坐标。
        h.player.setPose(h.player.input.shiftKeyDown ? Pose.CROUCHING : Pose.STANDING); h.nextTick();
    }
    public interface WrenchContract {
        default InteractionResult onSneakWrenched(BlockState state, UseOnContext context) { throw new AssertionError("shape inspection must not invoke wrench logic"); }
    }
    public static class DefaultBlock implements WrenchContract {}
    public static final class StrippingBlock implements WrenchContract {
        public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) { throw new AssertionError("shape inspection must not remove a shell"); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
