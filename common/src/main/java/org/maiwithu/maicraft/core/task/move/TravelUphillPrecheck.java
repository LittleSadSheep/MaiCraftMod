// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * travel 精确目标在头顶方向时的出发前预检。
 *
 * <p>带 y 提示的 travel 目标可能明显高于目标柱列的局部地表（调用方拿错高度、或误把
 * 「向上离开」当成精确坐标）。开 {@code may_alter_terrain} 后，规划器对此的答案是露天
 * 垫柱——破坏性地貌改变且事后留柱。本预检在出发前读目标柱列，把两种垫柱形态如实挡下：
 * 柱列通畅时的露天垫柱（拒绝并指路到地表高度），柱列中段有遮断时的强行上掘
 * （拒绝并指路绕行），都不再让身体掘到一半才失败。
 *
 * <p>目标接近地表（小台阶爬升）或扫描范围内找不到支撑（事实未知）时不拦截，
 * 维持原有规划行为——预检只拦「明显悬空」的目标，不替调用方重新解释坐标。
 */
final class TravelUphillPrecheck {

    enum Verdict {
        /** 正常放行：无需垫柱、小台阶爬升，或地表事实未知。 */
        ALLOW,
        /** 目标明显高于局部地表且柱列通畅：到达只能露天垫柱，出发前拒绝。 */
        OPEN_AIR_PILLAR,
        /** 目标高于地表且柱列中段有遮断：垫柱爬不通，出发前拒绝。 */
        COLUMN_OBSTRUCTED
    }

    record Result(Verdict verdict, int surfaceY, int rise, BlockPos obstruction) {
        static final Result ALLOW = new Result(Verdict.ALLOW, Integer.MIN_VALUE, 0, null);
        boolean gated() { return verdict != Verdict.ALLOW; }
    }

    /** 站上目标所需的最大无支撑爬升；以内视为正常台阶/短 scaffold，交给规划器自行处理。 */
    static final int MAX_PILLAR_RISE = 3;
    /** 目标柱列向下的支撑扫描深度；扫不到支撑按「事实未知」放行，不构成不存在的证据。 */
    static final int SURFACE_SCAN_DEPTH = 32;

    private TravelUphillPrecheck() {
    }

    /**
     * 读目标柱列得出预检结论。{@code feetY} 为当前脚位高度；只在目标确实在头顶方向时
     * 才值得扫描，调用方负责保证。支撑取柱列内从目标格向下的第一个有碰撞方块；
     * 通畅性检查从支撑顶到目标头顶一格——垫柱通道与身体占位都必须无碰撞。
     */
    static Result evaluate(Level level, BlockPos target, int feetY) {
        if (target.getY() <= feetY) return Result.ALLOW;
        int supportY = Integer.MIN_VALUE;
        int lowest = target.getY() - SURFACE_SCAN_DEPTH;
        for (int y = target.getY() - 1; y >= lowest; y--) {
            BlockPos at = new BlockPos(target.getX(), y, target.getZ());
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                supportY = y;
                break;
            }
        }
        if (supportY == Integer.MIN_VALUE) {
            // 扫描范围内没有支撑：悬空目标的事实未知，不拦截，维持原有规划行为。
            return Result.ALLOW;
        }
        int rise = target.getY() - (supportY + 1);
        BlockPos obstruction = null;
        // 支撑顶之上到目标头顶：柱块通道（supportY+1..targetY-1）与身体占位（targetY、targetY+1）。
        for (int y = supportY + 1; y <= target.getY() + 1; y++) {
            BlockPos at = new BlockPos(target.getX(), y, target.getZ());
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                obstruction = at;
                break;
            }
        }
        if (rise <= MAX_PILLAR_RISE) return Result.ALLOW;
        if (obstruction != null) {
            return new Result(Verdict.COLUMN_OBSTRUCTED, supportY, rise, obstruction);
        }
        return new Result(Verdict.OPEN_AIR_PILLAR, supportY, rise, null);
    }

    /** 拒绝回执的指路文本；坐标在成功过公平闸的前提下直接给出（find_block 先例）。 */
    static String advice(Result result, BlockPos target) {
        return switch (result.verdict()) {
            case ALLOW -> "";
            case OPEN_AIR_PILLAR -> " Refused before departure: the target sits " + result.rise()
                    + " blocks above the highest support found in its column (surface y="
                    + result.surfaceY() + "); reaching it would require building an open-air"
                    + " pillar that alters the landscape and is left standing. Travel to ("
                    + target.getX() + "," + (result.surfaceY() + 1) + "," + target.getZ()
                    + ") or re-target with the local surface height.";
            case COLUMN_OBSTRUCTED -> " Refused before departure: the column between the surface"
                    + " and the target is obstructed at " + result.obstruction().toShortString()
                    + ", so the ascent cannot be scaffolded straight up. Travel to the surface"
                    + " height (" + target.getX() + "," + (result.surfaceY() + 1) + ","
                    + target.getZ() + ") or approach the target from an adjacent column.";
        };
    }
}
