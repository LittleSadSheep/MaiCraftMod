// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.chain.SettleChain;

/**
 * 调用真实的贴边安定反射：任务释放后站在深落差边缘触发潜行退避，
 * 重心离开边缘线或身体不在地面时交还控制，摔落免疫的身体不触发。
 */
public final class SettleChainControlTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var w = new InteractionWorldTestHarness()) {
            // 窒息判定会读实体尺寸与挖掘药效；夹具角色绕过构造器创建，先补齐再跑任何场景。
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "dimensions")
                    .set(w.player, net.minecraft.world.entity.EntityDimensions.scalable(0.6F, 1.8F));
            ActorControlTestHarness.field(net.minecraft.world.entity.LivingEntity.class, "activeEffects")
                    .set(w.player, new java.util.HashMap<>());
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "fluidOnEyes")
                    .set(w.player, new java.util.HashSet<>());
            // 平台 (x,4,z) x,z∈1..8；玩家站 y=5，东侧 x≥9 是扫满四格都没有支撑的深渊。
            for (int x = 1; x <= 8; x++) for (int z = 1; z <= 8; z++)
                w.set(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
            var player = w.player;
            player.setDeltaMovement(Vec3.ZERO);
            var chain = new SettleChain();

            // 脚位格 (8,5,2)，重心距东侧边缘线 0.25 格：贴边站立必须触发。
            w.position(new Vec3(8.75, 5, 2.5));
            w.nextTick();
            check(chain.canRun(player), "a released body beside a deep drop must trigger the settle reflex");

            var movement = BodyControlPort.Movement.STOPPED;
            for (int tick = 0; tick < 20 && movement.forward() == 0; tick++) {
                chain.tick(player);
                movement = (BodyControlPort.Movement) ActorControlTestHarness.field(
                        DefaultBodyControlPort.class, "movement").get(w.h.body);
                w.nextTick();
            }
            check(movement.forward() > 0 && movement.sneaking() && !movement.jumping(),
                    "the reflex backs away from the edge while sneaking, without jumping");

            // 重心退到距边缘 0.95 格且无残余动量：安定完成，交还控制并松键。
            player.setDeltaMovement(Vec3.ZERO);
            w.position(new Vec3(8.05, 5, 2.5));
            w.nextTick();
            check(!chain.canRun(player), "a settled stance away from the edge releases the reflex");
            chain.tick(player);
            movement = (BodyControlPort.Movement) ActorControlTestHarness.field(
                    DefaultBodyControlPort.class, "movement").get(w.h.body);
            check(movement.forward() == 0 && !movement.sneaking(),
                    "finishing the settle episode halts movement inputs");

            // 空中的保护归落地救援链；贴边判定不接管尚未落地的身体。
            w.position(new Vec3(8.75, 5, 2.5));
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "onGround")
                    .setBoolean(player, false);
            w.nextTick();
            check(!chain.canRun(player), "an airborne body stays with the landing rescue chain");
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "onGround")
                    .setBoolean(player, true);

            // 摔落免疫（创造类工作规则）的身体不需要跌落防护反射。
            player.getAbilities().instabuild = true;
            w.nextTick();
            check(!chain.canRun(player), "a fall-immune profile skips the settle reflex");
            player.getAbilities().instabuild = false;

            // 重试闸门：同一反射实例完成一集后能再次触发，但判定通过才接管。
            w.position(new Vec3(8.75, 5, 2.5));
            w.nextTick();
            check(chain.canRun(player), "a fresh episode can start after the previous one finished");

            // 窒息逃逸：释放窗口内眼位在实心方块里（005 局围困形态），反射触发原生挖掘，
            // 致窒方块清除后窒息解除即收尾——只动那一格，脱困路线仍归调用方。
            var eyeBlock = new BlockPos(2, 6, 2);
            w.set(eyeBlock, Blocks.STONE.defaultBlockState());
            w.position(new Vec3(2.5, 5, 2.5));
            w.nextTick();
            check(player.isInWall(), "fixture: the eye sits inside a suffocating stone block");
            check(chain.canRun(player), "a released body suffocating inside a block must trigger the settle reflex");
            chain.tick(player);
            check(suffocating(chain) != null, "the escape issues a native break on the suffocating block");
            w.set(eyeBlock, Blocks.AIR.defaultBlockState());
            w.nextTick();
            chain.tick(player);
            check(!player.isInWall() && suffocating(chain) == null,
                    "clearing the suffocating block finishes the escape episode");

            // 场景 E 的口径差（2026-10-05 实机三轮）：窒息被击退/推挤提前"翻出"原版薄盒判定，
            // 眼位方块却仍是致窒实心格——反射必须按眼位方块本身继续触发，否则围困永不逃逸。
            // 用 noPhysics 强制关掉原版 isInWall 来模拟该形态。
            w.set(eyeBlock, Blocks.STONE.defaultBlockState());
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "noPhysics")
                    .setBoolean(player, true);
            w.nextTick();
            check(!player.isInWall(), "fixture: the vanilla thin-box predicate no longer sees the wall");
            check(chain.canRun(player), "a suffocating eye cell triggers the escape even when the vanilla predicate misses it");
            check(chain.urgentBodyRescue(player),
                    "suffocation declares the urgent-rescue exemption while the eye cell is sealed");
            // 调度层旧形态复现：身体仍被在岗任务持有（taskHoldsBody=true）且眼位被塞——
            // 修复前释放窗口判定直接跳过本反射，窒息无人接管；修复后紧急豁免必须胜出。
            check(org.maiwithu.maicraft.task.TaskSelector.select(
                    java.util.List.of(chain), null, new OccupyingStubTask(), java.util.List.of(), player, true) == chain,
                    "a held body with a sealed eye cell is still handed to the suffocation escape");
            chain.tick(player);
            check(suffocating(chain) != null, "the escape still issues a native break on the suffocating block");
            w.set(eyeBlock, Blocks.AIR.defaultBlockState());
            w.nextTick();
            chain.tick(player);
            check(suffocating(chain) == null, "clearing the block closes the widened escape episode");
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "noPhysics")
                    .setBoolean(player, false);
            check(!chain.urgentBodyRescue(player),
                    "the urgent-rescue exemption only covers the suffocation branch, not the edge retreat");

            // 批四 A 实机形态（2026-10-05）：身体被滑移/睡眠传送挪出眼位格后，薄盒与眼位格
            // 判定同时失明，而头部所在格仍是致窒实心格。按身体占据格判定必须继续触发，
            // 且逃逸挖的是检出的围困格本身，不再以眼位点重新定位挖掘目标。
            w.position(new Vec3(2.5, 5.5, 2.5)); // 脚位半格上移：眼位点落到 (2,7,2) 的空气格，头位格仍是 (2,6,2)
            w.set(eyeBlock, Blocks.STONE.defaultBlockState());
            w.nextTick();
            BlockPos eyeCell = BlockPos.containing(player.getEyePosition());
            check(!player.isInWall() && !player.level().getBlockState(eyeCell).isSuffocating(player.level(), eyeCell),
                    "fixture: the eye point sits in a clear cell while the head cell holds the stone");
            check(chain.canRun(player),
                    "a body whose head cell is sealed still triggers the escape even with the eye point clear");
            check(chain.urgentBodyRescue(player),
                    "the head-cell seal also declares the urgent-rescue exemption");
            chain.tick(player);
            check(eyeBlock.equals(suffocating(chain)), "the escape digs the detected trapped cell, not the eye point");
            w.set(eyeBlock, Blocks.AIR.defaultBlockState());
            w.nextTick();
            chain.tick(player);
            check(suffocating(chain) == null, "clearing the head cell closes the escape episode");
            w.position(new Vec3(2.5, 5, 2.5));
            w.nextTick();
            check(!chain.canRun(player), "a body with no suffocating cell in its occupied cells stays released");
        }
        System.out.println("SettleChainControlTest: passed");
    }

    /** 占身桩任务：canRun 恒真，用于让选人器进入"在岗任务持有身体"分支。 */
    private static final class OccupyingStubTask implements org.maiwithu.maicraft.task.Task {
        @Override public boolean canRun(net.minecraft.client.player.LocalPlayer companion) { return true; }
        @Override public org.maiwithu.maicraft.task.TaskState tick(net.minecraft.client.player.LocalPlayer companion) {
            return org.maiwithu.maicraft.task.TaskState.RUNNING;
        }
        @Override public void stop(net.minecraft.client.player.LocalPlayer companion, StopReason why) { }
        @Override public String name() { return "occupying_stub"; }
    }

    private static BlockPos suffocating(SettleChain chain) throws Exception {
        var field = SettleChain.class.getDeclaredField("suffocating");
        field.setAccessible(true);
        return (BlockPos) field.get(chain);
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
