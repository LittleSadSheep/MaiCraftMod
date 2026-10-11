// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;

/**
 * 机械合成器的两项设置与连入口的点击框，纯函数。crafter.slot 这一台格里放什么（物品 ID，空着写 empty）：
 * 几台连成一片，每台放配方的一格，凑齐了一起做；crafter.pointing 箭头朝哪（up / down / left / right），
 * 料顺着箭头送到出口那一台。放料、取料是手持料（或空着手）右键正面，改箭头是拿扳手右键正面。
 */
final class CrafterSettings {

    static final String SLOT = "crafter.slot";
    static final String POINTING = "crafter.pointing";
    static final String EMPTY = "empty";
    /** 正面那一片的厚度：点在正面上，往里收一点避开边线。 */
    private static final double FACE_DEPTH = 0.02;
    private static final double FACE_INSET = 0.15;
    /** 连入口的那条边：沿边往里 6/16 格宽的一条（Create 的 EdgeInteractionHandler.getBB）。类别：游戏事实。 */
    private static final double EDGE_MARGIN = 10 / 16.0;

    private CrafterSettings() {
    }

    /** 两项设置的现值；不是机械合成器时没有。 */
    static List<MachineSetting> of(Optional<CreateStations.CrafterReading> reading) {
        if (reading.isEmpty()) return List.of();
        List<MachineSetting> settings = new ArrayList<>();
        settings.add(new MachineSetting(SLOT, slotValue(reading.get().slot()), List.of(),
                "这一台格里放什么：写物品 ID（手持它右键正面放进去），empty 空着手右键取出；格里已有别的先取出再放"));
        settings.add(new MachineSetting(POINTING, reading.get().pointing(), List.of("up", "down", "left", "right"),
                "箭头朝哪：料顺着箭头送到出口那一台；拿扳手右键正面换，一次转一下"));
        return settings;
    }

    static String slotValue(ItemStack slot) {
        return slot.isEmpty() ? EMPTY : BuiltInRegistries.ITEM.getKey(slot.getItem()).toString();
    }

    /** 正面上的点击框（世界坐标）：正面那一片，四边往里收一点。 */
    static AABB front(BlockPos at, Direction facing) {
        double x = facing.getAxis() == Direction.Axis.X ? 0 : FACE_INSET;
        double y = facing.getAxis() == Direction.Axis.Y ? 0 : FACE_INSET;
        double z = facing.getAxis() == Direction.Axis.Z ? 0 : FACE_INSET;
        return slab(at, facing).deflate(x, y, z);
    }

    /**
     * 连入口要点的那一块（世界坐标）：这台合成器某一面上、靠 toward 那条边的一条，Create 只认点在这一条里的扳手右键。
     * 先按 Create 的式子取整格里靠 toward 那一侧的一条（伸出格外的部分不算），再压成 face 那一面上薄薄一片。
     */
    static AABB edge(BlockPos at, Direction face, Direction toward) {
        double absX = Math.abs(toward.getStepX()) * EDGE_MARGIN;
        double absY = Math.abs(toward.getStepY()) * EDGE_MARGIN;
        double absZ = Math.abs(toward.getStepZ()) * EDGE_MARGIN;
        AABB strip = new AABB(at).contract(absX, absY, absZ).move(absX / 2, absY / 2, absZ / 2)
                .move(toward.getStepX() / 2.0, toward.getStepY() / 2.0, toward.getStepZ() / 2.0);
        return strip.intersect(slab(at, face));
    }

    // 一格某一面上薄薄的一整片（世界坐标）。
    private static AABB slab(BlockPos at, Direction face) {
        AABB local = switch (face) {
            case UP -> new AABB(0, 1 - FACE_DEPTH, 0, 1, 1, 1);
            case DOWN -> new AABB(0, 0, 0, 1, FACE_DEPTH, 1);
            case EAST -> new AABB(1 - FACE_DEPTH, 0, 0, 1, 1, 1);
            case WEST -> new AABB(0, 0, 0, FACE_DEPTH, 1, 1);
            case SOUTH -> new AABB(0, 0, 1 - FACE_DEPTH, 1, 1, 1);
            case NORTH -> new AABB(0, 0, 0, 1, 1, FACE_DEPTH);
        };
        return local.move(at.getX(), at.getY(), at.getZ());
    }
}
