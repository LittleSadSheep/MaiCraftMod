// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/**
 * 门框审计与原版 PortalShape 口径对齐：审计认可的框 vanilla 必然成型，vanilla 拒绝的框
 * 审计必须给出逐格偏差——点火后门不成型的差异只能出在真实世界，不能出在口径。
 *
 * <p>口径对齐断言用空气内格做：夹具不绑定方块标签，vanilla 的 {@code BlockTags.FIRE} 口径
 * 在测试 JVM 里是空集（火会被当成非空内格）；火不阻碍成型的等价性由审计自身的火/空气同判
 * 断言承载，火口径的实机行为由点火窗口的回执证据在实机验收。
 */
public final class NetherPortalVanillaAuditTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            try (var world = new InteractionWorldTestHarness()) {
                var frame = new NetherPortalFrame(new BlockPos(5, 2, 5), axis, 2, 3);
                frame.frame().forEach(p -> world.set(p, Blocks.OBSIDIAN.defaultBlockState()));
                BlockPos fireCell = frame.cell(0, 0);

                // 火落在内格：vanilla 的 isEmpty 认火，审计同样不把火当阻碍。
                world.set(fireCell, Blocks.FIRE.defaultBlockState());
                var withFire = NetherPortalVanillaAudit.audit(world.level::getBlockState, fireCell);
                check(withFire.frameValid() && withFire.wouldFormPortal() && withFire.offenses().isEmpty(),
                        "a complete frame with interior fire still forms a portal: " + withFire);
                world.set(fireCell, Blocks.AIR.defaultBlockState());
                var withAir = NetherPortalVanillaAudit.audit(world.level::getBlockState, fireCell);
                check(withAir.frameValid() && withAir.wouldFormPortal() && withAir.offenses().isEmpty(),
                        "air and fire interiors audit identically: " + withAir);
                check(withFire.width() == withAir.width() && withFire.height() == withAir.height()
                                && withFire.bottomLeft().equals(withAir.bottomLeft()),
                        "the fire cell does not perturb the scanned geometry");
                check(withAir.axis() == axis && withAir.width() == 2 && withAir.height() == 3
                                && frame.interior().stream().anyMatch(p -> p.equals(withAir.bottomLeft())
                                        && p.getY() == frame.origin().getY()),
                        "the audit anchors on a real bottom interior cell and recovers 2x3: " + withAir);
                // 口径对齐：vanilla 扫描器在同一个世界上认可同一扇空气内格的门。
                Optional<PortalShape> vanilla = PortalShape.findEmptyPortalShape(world.level, fireCell, Direction.Axis.X);
                check(vanilla.isPresent() && vanilla.get().isValid(),
                        "vanilla PortalShape agrees the frame is forming");

                // 有效门框点火 → 成型 → 推进测试刻：夹具内没有任何 mod 系统移除传送门方块。
                // createPortalBlocks 的原版 setBlock 通路依赖服务端预测处理器，夹具里按同款状态铺装内格。
                frame.interior().forEach(p -> world.set(p, Blocks.NETHER_PORTAL.defaultBlockState()
                        .setValue(NetherPortalBlock.AXIS, axis)));
                for (int tick = 0; tick < 40; tick++) world.nextTick();
                check(frame.active(world.level::getBlockState),
                        "a formed portal survives the harness ticks untouched");
                var after = NetherPortalVanillaAudit.audit(world.level::getBlockState, fireCell);
                check(after.frameValid() && !after.wouldFormPortal(),
                        "after formation the audit flips would_form_portal, matching findEmptyPortalShape");
                check(PortalShape.findEmptyPortalShape(world.level, fireCell, Direction.Axis.X).isEmpty(),
                        "vanilla no longer reports an empty portal shape once portal blocks fill the interior");

                // 侧柱混入圆石：vanilla 拒绝，审计给出同格偏差（vanilla 侧用空气内格，避开夹具未绑定的火标签）。
                world.set(frame.cell(-1, 1), Blocks.COBBLESTONE.defaultBlockState());
                world.set(fireCell, Blocks.FIRE.defaultBlockState());
                var brokenSide = NetherPortalVanillaAudit.audit(world.level::getBlockState, fireCell);
                world.set(fireCell, Blocks.AIR.defaultBlockState());
                var vanillaSide = new PortalShape(world.level, fireCell, axis);
                check(!brokenSide.frameValid() && !brokenSide.wouldFormPortal()
                                && brokenSide.offenses().stream().anyMatch(o ->
                                        "side_frame".equals(o.role()) && o.pos().equals(frame.cell(-1, 1))
                                                && o.observed().contains("cobblestone")),
                        "a cobblestone pillar cell is reported as a side_frame offense: " + brokenSide);
                check(!vanillaSide.isValid(), "vanilla rejects the same broken pillar");

                // 顶部内格被堵：审计量到的高度不足、点名内格，与 vanilla 的提前停点一致。
                try (var blocked = new InteractionWorldTestHarness()) {
                    var good = new NetherPortalFrame(new BlockPos(5, 2, 5), axis, 2, 3);
                    good.frame().forEach(p -> blocked.set(p, Blocks.OBSIDIAN.defaultBlockState()));
                    blocked.set(good.cell(1, 2), Blocks.STONE.defaultBlockState());
                    BlockPos seed = good.cell(0, 0);
                    blocked.set(seed, Blocks.FIRE.defaultBlockState());
                    var blockedAudit = NetherPortalVanillaAudit.audit(blocked.level::getBlockState, seed);
                    check(!blockedAudit.wouldFormPortal() && blockedAudit.offenses().stream().anyMatch(o ->
                                    "interior_blocked".equals(o.role()) && o.pos().equals(good.cell(1, 2))),
                            "a blocked top interior cell is reported as interior_blocked: " + blockedAudit);
                }
            }
        }
        // 未加载格绝不冒充空气：审计如实报 unloaded 且不成型。
        var states = new java.util.HashMap<BlockPos, BlockState>();
        var unloadedFrame = new NetherPortalFrame(BlockPos.ZERO, Direction.Axis.X, 2, 3);
        unloadedFrame.frame().forEach(p -> states.put(p, Blocks.OBSIDIAN.defaultBlockState()));
        java.util.function.Function<BlockPos, BlockState> read =
                p -> p.equals(unloadedFrame.cell(-1, 1)) ? null : states.getOrDefault(p, Blocks.AIR.defaultBlockState());
        var unloadedAudit = NetherPortalVanillaAudit.audit(read, unloadedFrame.cell(0, 0));
        check(!unloadedAudit.wouldFormPortal() && unloadedAudit.offenses().stream().anyMatch(o ->
                        o.role().endsWith("unloaded") && o.observed().equals("unloaded")),
                "an unloaded frame cell is reported as unloaded, never as air: " + unloadedAudit);
        System.out.println("NetherPortalVanillaAuditTest: vanilla agreement and per-cell offense reporting passed");
    }
}
