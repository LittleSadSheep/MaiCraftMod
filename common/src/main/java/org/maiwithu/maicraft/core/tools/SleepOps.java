package org.maiwithu.maicraft.core.tools;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.core.task.sleep.SleepTaskRecord;

/** Read-only bed target resolution for the receipt-owned sleep task. */
public final class SleepOps {

    private static final int REACH_H = 3;
    private static final int REACH_V = 2;
    private static final long TIMEOUT_TICKS = 15L * 20L;

    public record Plan(SleepTaskRecord task, String refusal) {
        public boolean executable() {
            return task != null;
        }
    }

    public Plan plan(
            Integer x, Integer y, Integer z, LocalPlayer self, ToolContext context) {
        boolean anyCoordinate = x != null || y != null || z != null;
        boolean allCoordinates = x != null && y != null && z != null;
        if (anyCoordinate && !allCoordinates) {
            return new Plan(null, "a specific bed needs all of x, y and z");
        }
        BlockPos bedHead = allCoordinates
                ? headOf(self, new BlockPos(x, y, z))
                : nearestBedHeadInReach(self);
        if (bedHead == null) {
            String carried = carriedBed(self);
            String base = allCoordinates
                    ? "there is no loaded bed at those coordinates"
                    : "there is no loaded bed within first-person reach";
            String recovery = carried != null
                    ? " You are carrying " + carried
                            + "; choose a safe flat site, place it, then try sleep again."
                    : " Scan loaded terrain for #minecraft:beds. If none exists, decide openly "
                            + "whether to gather wool and wood, trade/find a bed, or wait out the night.";
            return new Plan(null, base + "." + recovery);
        }
        return new Plan(new SleepTaskRecord(
                context.toolCallId(), context.deadline(TIMEOUT_TICKS), bedHead), null);
    }

    private static BlockPos nearestBedHeadInReach(LocalPlayer self) {
        BlockPos me = self.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                me.offset(-REACH_H, -REACH_V, -REACH_H),
                me.offset(REACH_H, REACH_V, REACH_H))) {
            BlockPos head = headOf(self, pos);
            if (head == null) {
                continue;
            }
            double distance = head.distSqr(me);
            if (distance < bestDistance) {
                best = head;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static BlockPos headOf(LocalPlayer self, BlockPos pos) {
        if (!self.level().isLoaded(pos)) {
            return null;
        }
        BlockState state = self.level().getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return null;
        }
        BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD
                ? pos.immutable()
                : pos.relative(state.getValue(BedBlock.FACING)).immutable();
        return self.level().isLoaded(head) ? head : null;
    }

    private static String carriedBed(LocalPlayer self) {
        int usableSlots = Math.min(36, self.getInventory().getContainerSize());
        for (int slot = 0; slot < usableSlots; slot++) {
            ItemStack stack = self.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof BedBlock) {
                return BuiltInRegistries.ITEM.getKey(stack.getItem())
                        + " x" + stack.getCount();
            }
        }
        return null;
    }
}
