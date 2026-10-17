// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator.ChainShape;

/**
 * 连锁形状在 FTB Ultimine 里叫什么、选区该长什么样：FTB 的选区要符合这个形状，才认它是按这个形状刚算出来的
 * （切形状时可能先收到按旧形状算的那一份）。
 */
final class ChainShapes {

    private ChainShapes() {}

    /** FTB 里这个形状注册的名字。 */
    static String ftbName(ChainShape shape) {
        return switch (shape) {
            case FACE_SQUARE -> "ftbultimine:small_square";
            case CONNECTED -> "ftbultimine:shapeless";
            case DOWNWARD_LINE -> "ftbultimine:mining_tunnel";
            case STRAIGHT_LINE -> "ftbultimine:small_tunnel";
        };
    }

    /** 面板上这个形状的中文名，写进说明。 */
    static String shownName(ChainShape shape) {
        return switch (shape) {
            case FACE_SQUARE -> "小型方形";
            case CONNECTED -> "无形状";
            case DOWNWARD_LINE -> "采矿通道";
            case STRAIGHT_LINE -> "小型通道";
        };
    }

    /** 命中面上以触发格为中心的 3×3：上下面是水平的九格，侧面是竖着的九格。 */
    static List<BlockPos> square(BlockPos origin, Direction face) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                cells.add(switch (face.getAxis()) {
                    case X -> origin.offset(0, a, b);
                    case Y -> origin.offset(a, 0, b);
                    case Z -> origin.offset(a, b, 0);
                });
            }
        }
        return cells;
    }

    /** 选区符不符合这个形状：方形在命中面 3×3 里；两种通道在从触发格往里的那一条线上；无形状是连成一片的。 */
    static boolean fits(ChainShape shape, BlockPos origin, Direction face, List<BlockPos> cells) {
        if (!cells.contains(origin)) return false;
        return switch (shape) {
            case FACE_SQUARE -> square(origin, face).containsAll(cells);
            case STRAIGHT_LINE -> onLine(origin, face.getOpposite(), 0, cells);
            case DOWNWARD_LINE -> downward(origin, face, cells);
            case CONNECTED -> connected(origin, cells);
        };
    }

    // 采矿通道：命中侧面时顺着面朝里斜着往下；命中顶面时按角色朝向，朝向从选区的第二格认。
    private static boolean downward(BlockPos origin, Direction face, List<BlockPos> cells) {
        if (face.getAxis().isHorizontal()) return onLine(origin, face.getOpposite(), -1, cells);
        for (Direction heading : Direction.Plane.HORIZONTAL) {
            if (onLine(origin, heading, -1, cells)) return true;
        }
        return false;
    }

    // 每一格都是触发格往 heading 走 k 格、同时上下挪 k×rise 格（k ≥ 0）。
    private static boolean onLine(BlockPos origin, Direction heading, int rise, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            int dx = cell.getX() - origin.getX();
            int dy = cell.getY() - origin.getY();
            int dz = cell.getZ() - origin.getZ();
            int k = dx * heading.getStepX() + dy * heading.getStepY() + dz * heading.getStepZ();
            if (k < 0 || !cell.equals(origin.relative(heading, k).above(k * rise))) return false;
        }
        return true;
    }

    // 从触发格出发，相邻（含斜角）一格一格连得到选区里的每一格。
    private static boolean connected(BlockPos origin, List<BlockPos> cells) {
        Set<BlockPos> left = new HashSet<>(cells);
        Deque<BlockPos> frontier = new ArrayDeque<>(List.of(origin));
        left.remove(origin);
        while (!frontier.isEmpty() && !left.isEmpty()) {
            BlockPos at = frontier.poll();
            for (BlockPos next : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                BlockPos fixed = next.immutable();
                if (left.remove(fixed)) frontier.add(fixed);
            }
        }
        return left.isEmpty();
    }
}
