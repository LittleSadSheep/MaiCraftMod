// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineBreak;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineControl;
import org.maiwithu.maicraft.core.integration.ultimine.UltimineSession;

/** 真实挖掘端口配合可控 FTB 回执，回放持键、原生部分破坏和松键等待；不把预览当产出。 */
public final class UltimineBreakTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        scenario(false); scenario(true);
        System.out.println("UltimineBreakTest: native break, partial effects and key release passed");
    }
    private static void scenario(boolean partial) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.enableCraftingTransactions(); h.h.minecraft.screen = null; h.position(new Vec3(8.5, 1, 8.5));
            ActorControlTestHarness.field(LivingEntity.class, "activeEffects").set(h.player, new HashMap<>());
            BlockPos origin = new BlockPos(8, 2, 10);
            List<BlockPos> selection = List.of(origin, origin.south(), origin.south(2));
            selection.forEach(at -> h.set(at, Blocks.DIRT.defaultBlockState()));
            var nativeControl = new NativeControl(selection);
            int[] swings = {0};
            h.mode.breaking = at -> {
                check(at.equals(origin), "only the original native target receives a manual breaking request");
                if (++swings[0] < 3) return;
                h.set(origin, Blocks.AIR.defaultBlockState());
                if (!partial) selection.forEach(cell -> h.set(cell, Blocks.AIR.defaultBlockState()));
            };
            var action = new UltimineBreak(h.player, origin, Direction.NORTH, UltimineSession.Mode.SMALL_TUNNEL,
                    selection::contains, at -> false, nativeControl);
            UltimineBreak.Result result = null;
            for (int tick = 0; tick < 180; tick++) {
                h.nextTick(); result = action.tick(); DiscardFireTest.align(h);
                if (result.status() != UltimineBreak.Status.RUNNING) break;
            }
            check(result != null && result.status() == UltimineBreak.Status.COMPLETE, "native transaction completes: " + result);
            check(result.removed().size() == (partial ? 1 : 3) && !result.uncertain(), "only observed native effects are counted");
            check(result.evidence().get("remaining").equals(partial ? 2 : 0), "unbroken secondary blocks remain explicit");
            check(nativeControl.held >= 2 && nativeControl.released >= 3 && nativeControl.closed,
                    "chain key stays held through the native break and release is settled before handoff");
            check(h.mode.breakStarts == 1, "the controller submits the seed only once");
        }
    }
    static final class NativeControl implements UltimineControl {
        private final List<BlockPos> selected;
        int held, released; boolean closed;
        NativeControl(List<BlockPos> selected) { this.selected = selected; }
        public UltimineSession.Decision prepareSelection(LocalPlayerContext context, BlockHitResult hit,
                Predicate<BlockPos> allowed, Predicate<BlockPos> preserve) {
            return new UltimineSession.Decision(UltimineSession.Status.READY, "native_preview", selected, selected);
        }
        public UltimineSession.Decision tickInFlight(LocalPlayerContext context) { held++; return decision(UltimineSession.Status.READY); }
        public UltimineSession.Decision finish(LocalPlayerContext context) { return decision(++released < 3 ? UltimineSession.Status.WAITING : UltimineSession.Status.SINGLE_BLOCK); }
        public Map<String, Object> holdEvidence() { return Map.of("held_break_ticks", held); }
        public void close() { closed = true; }
        private UltimineSession.Decision decision(UltimineSession.Status status) { return new UltimineSession.Decision(status, "native_feedback", List.of(), List.of()); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
