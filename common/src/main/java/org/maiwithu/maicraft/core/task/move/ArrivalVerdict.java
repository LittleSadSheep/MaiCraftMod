// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

/**
 * 到达回执分级：把「这次到达是精确落位还是容差内到达」算成可核验的字段。
 * 只读几何事实，不改变到达判定本身；判定逻辑仍由任务与导航持有，
 * 调用方按等级与剩余距离自行决定是否继续逼近。
 */
public final class ArrivalVerdict {

    public static final String EXACT = "arrived_exact";
    public static final String WITHIN_TOLERANCE = "arrived_within_tolerance";

    private final String grade;
    /** 身体到目的地格中心的水平直线距离，按 0.1 格取整。 */
    private final double remainingHorizontal;
    /** 脚位高度与目标层的差（正数表示身体高于目标）；无高度提示时为 NaN。 */
    private final double remainingVertical;
    /** 目标相对身体的方向词：八向水平罗盘 + 目标在上/在下/同层。 */
    private final String direction;

    private ArrivalVerdict(String grade, double remainingHorizontal, double remainingVertical, String direction) {
        this.grade = grade;
        this.remainingHorizontal = remainingHorizontal;
        this.remainingVertical = remainingVertical;
        this.direction = direction;
    }

    /**
     * 按身体浮点坐标与目的地格计算分级。精确等级要求水平方向站在目的地格内，
     * 且带高度提示时脚位也在目标层；容差等级表示虽在到达范围内但不在目的地格。
     * 判定与任务的到达条件同源（同一套格成员口径），不存在第三种分级。
     *
     * @param yHintPresent 目的地是否携带 Y 提示；无提示时垂直分量不参与分级
     */
    public static ArrivalVerdict of(double px, double py, double pz,
                                    int bx, int by, int bz, boolean yHintPresent) {
        double dx = (bx + 0.5) - px;
        double dz = (bz + 0.5) - pz;
        double horizontal = round(Math.sqrt(dx * dx + dz * dz));
        double vertical = yHintPresent ? round(py - by) : Double.NaN;
        boolean exact = Math.floor(px) == bx && Math.floor(pz) == bz
                && (!yHintPresent || Math.floor(py) == by);
        return new ArrivalVerdict(exact ? EXACT : WITHIN_TOLERANCE,
                horizontal, vertical, describe(dx, dz, py, by, yHintPresent));
    }

    /** Minecraft 坐标系：+x 东、+z 南；先按八向水平罗盘定向，再叠目标相对身体的高低。 */
    private static String describe(double dx, double dz, double py, int by, boolean yHintPresent) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int sector = ((int) Math.round(angle / 45.0) + 8) % 8;
        String compass = switch (sector) {
            case 0 -> "east";
            case 1 -> "south-east";
            case 2 -> "south";
            case 3 -> "south-west";
            case 4 -> "west";
            case 5 -> "north-west";
            case 6 -> "north";
            default -> "north-east";
        };
        if (!yHintPresent) return compass;
        String verticalWord;
        if (Math.floor(py) > by) verticalWord = "target below";
        else if (Math.floor(py) < by) verticalWord = "target above";
        else verticalWord = "same layer";
        return compass + ", " + verticalWord;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public String grade() { return grade; }

    public double remainingHorizontal() { return remainingHorizontal; }

    /** 无高度提示时为 NaN；调用方用 {@link #hasVerticalHint()} 区分。 */
    public double remainingVertical() { return remainingVertical; }

    public boolean hasVerticalHint() { return !Double.isNaN(remainingVertical); }

    public String direction() { return direction; }
}
