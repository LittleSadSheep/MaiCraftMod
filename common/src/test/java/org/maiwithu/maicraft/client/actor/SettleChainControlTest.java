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
        }
        System.out.println("SettleChainControlTest: passed");
    }

    private static BlockPos suffocating(SettleChain chain) throws Exception {
        var field = SettleChain.class.getDeclaredField("suffocating");
        field.setAccessible(true);
        return (BlockPos) field.get(chain);
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
