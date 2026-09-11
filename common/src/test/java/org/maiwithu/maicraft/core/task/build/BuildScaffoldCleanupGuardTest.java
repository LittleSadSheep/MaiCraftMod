// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.task.TaskState;

/** Cleanup's ownership and safety gates run before any native dig can be submitted. */
public final class BuildScaffoldCleanupGuardTest {
    private static final BlockPos TARGET = new BlockPos(6, 1, 6);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var info = new net.minecraft.client.multiplayer.PlayerInfo(
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "cleanup-guard"), false);
            var cachedInfo = net.minecraft.client.player.AbstractClientPlayer.class.getDeclaredField("playerInfo");
            cachedInfo.setAccessible(true); cachedInfo.set(h.player, info);
            var dimensions = net.minecraft.world.entity.Entity.class.getDeclaredField("dimensions");
            dimensions.setAccessible(true);
            dimensions.set(h.player, h.player.getDimensions(net.minecraft.world.entity.Pose.STANDING));
            h.set(TARGET, Blocks.COBBLESTONE.defaultBlockState());
            var unowned = task(h, false, true);
            check(tick(unowned, "scaffoldBreakTick") == TaskState.FAILED, "a bare attempted coordinate grants no ownership");
            check("scaffold_cleanup_guard_changed".equals(field(unowned, "failureCode")), "unowned failure stays explicit");

            var changed = task(h, true, true);
            h.set(TARGET, Blocks.STONE.defaultBlockState());
            check(tick(changed, "scaffoldBreakTick") == TaskState.FAILED, "replaced support cannot be removed");
            h.set(TARGET, Blocks.COBBLESTONE.defaultBlockState());
            var protectedTask = task(h, true, true);
            check(NavigationSafetyContext.withProtectedArea(List.of(TARGET), List.of(),
                    () -> tick(protectedTask, "scaffoldBreakTick")) == TaskState.FAILED, "new mutation protection is enforced");
            check(tick(task(h, true, false), "scaffoldBreakTick") == TaskState.FAILED,
                    "the owner's current world guard remains authoritative");

            h.position(new Vec3(6.5, 2, 6.5));
            var underfoot = task(h, true, true);
            check(tick(underfoot, "scaffoldBreakTick") == TaskState.RUNNING, "foot support is not dug or immediately failed");
            check("SCAFFOLD_NAV".equals(field(underfoot, "phase").toString()), "unsafe footing requests a different stance");
            check(h.level.getBlockState(TARGET).is(Blocks.COBBLESTONE), "underfoot support remains in the live world");

            var vanished = task(h, true, true);
            h.set(TARGET, Blocks.AIR.defaultBlockState());
            check(tick(vanished, "scaffoldNavTick") == TaskState.RUNNING, "vanished support needs no visibility search");
            check("SCAFFOLD_BREAK".equals(field(vanished, "phase").toString()), "air proceeds to the existing cleanup receipt path");
            check(tick(vanished, "scaffoldBreakTick") == TaskState.RUNNING, "already removed support advances cleanup");
            check("SCAFFOLD_SELECT".equals(field(vanished, "phase").toString()), "remaining scaffolds can be processed");
            check(h.blockUses() == 0 && h.itemUses() == 0, "guard checks submit no native use actions");
        }
        System.out.println("BuildScaffoldCleanupGuardTest: ownership, replacement, protection, footing and vanished target passed");
    }

    private static FirstPersonBuildCompanionTask task(InteractionWorldTestHarness h, boolean owned, boolean allowed) throws Exception {
        var record = new BuildTaskRecord("cleanup-guard", 1000, List.of(), false);
        record.executionGuards(List.of(), p -> true, (p, pos) -> allowed, (p, pos) -> {});
        if (owned) record.scaffoldLedger().confirmed(TARGET, Blocks.COBBLESTONE.defaultBlockState());
        var task = new FirstPersonBuildCompanionTask(h.player, record);
        set(task, "scaffold", TARGET);
        set(task, "scaffoldCleanup", new BuildScaffoldCleanup(h.player, TARGET, LongSets.emptySet()));
        return task;
    }

    private static TaskState tick(FirstPersonBuildCompanionTask task, String method) {
        try {
            var callable = FirstPersonBuildCompanionTask.class.getDeclaredMethod(method);
            callable.setAccessible(true);
            return (TaskState) callable.invoke(task);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object field(Object task, String name) throws Exception {
        var field = FirstPersonBuildCompanionTask.class.getDeclaredField(name);
        field.setAccessible(true); return field.get(task);
    }
    private static void set(Object task, String name, Object value) throws Exception {
        var field = FirstPersonBuildCompanionTask.class.getDeclaredField(name);
        field.setAccessible(true); field.set(task, value);
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
