// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.move;

import java.util.Map;

/**
 * 到达回执分级契约：精确落位与容差内到达可程序化分辨，剩余距离与方向如实交付；
 * 自动落地保护未验证只作注记降级，不再否决已成立的到达事实。
 */
public final class MoveToArrivalReceiptTest {

    public static void main(String[] args) {
        exactArrivalGradesArrivedExact();
        toleranceArrivalCarriesRemainingDistanceAndDirection();
        verticalHintSeparatesSameLayerFromAbove();
        landingProtectionFailureDowngradesToAnnotation();
        exactAndToleranceNotesShareOneShape();
        System.out.println("MoveToArrivalReceiptTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void exactArrivalGradesArrivedExact() {
        // 身体站在目的地格内（格中心 358.5,-74.5），脚位与目标同层：精确落位。
        ArrivalVerdict v = ArrivalVerdict.of(358.3, 55.0, -74.6, 358, 55, -75, true);
        check(ArrivalVerdict.EXACT.equals(v.grade()), "standing in the destination cell must grade arrived_exact");
        check(Math.abs(v.remainingHorizontal() - 0.2) < 0.05, "remaining horizontal distance must reflect the true offset");
        check(Math.abs(v.remainingVertical()) < 0.001, "same-layer arrival has no vertical remainder");
        check(v.direction().contains("same layer"), "same-layer arrival must say so");
    }

    private static void toleranceArrivalCarriesRemainingDistanceAndDirection() {
        // 容差内但不在目的地格（070 实测形态：判到达却偏西约 3.2 格）。
        ArrivalVerdict v = ArrivalVerdict.of(356.25, 55.0, -72.5, 358, 55, -75, true);
        check(ArrivalVerdict.WITHIN_TOLERANCE.equals(v.grade()),
                "arrival outside the destination cell must grade arrived_within_tolerance");
        check(Math.abs(v.remainingHorizontal() - 3.0) < 0.05,
                "receipt must carry the remaining horizontal distance, got " + v.remainingHorizontal());
        check(v.direction().startsWith("north-east"), "target to +x/-z must read north-east, got " + v.direction());
        // 无高度提示时垂直分量不参与分级也不虚构数值。
        ArrivalVerdict column = ArrivalVerdict.of(10.5, 100.0, 10.5, 14, 0, 14, false);
        check(ArrivalVerdict.WITHIN_TOLERANCE.equals(column.grade()), "column offset grades within tolerance");
        check(!column.hasVerticalHint(), "without a Y hint no vertical remainder may be invented");
    }

    private static void verticalHintSeparatesSameLayerFromAbove() {
        // 082 实测形态：请求 y59 水面层，站在 y61 合格但无效——分级必须如实分开。
        ArrivalVerdict above = ArrivalVerdict.of(-3090.5, 61.0, 16.5, -3091, 59, 16, true);
        check(ArrivalVerdict.WITHIN_TOLERANCE.equals(above.grade()),
                "standing two layers above the hinted Y is not an exact arrival");
        check(Math.abs(above.remainingVertical() - 2.0) < 0.001, "vertical remainder must be the true delta");
        check(above.direction().endsWith("target below"), "target under the feet must read target below");
        ArrivalVerdict exact = ArrivalVerdict.of(-3090.4, 59.0, 16.2, -3091, 59, 16, true);
        check(ArrivalVerdict.EXACT.equals(exact.grade()), "same-cell same-layer stance grades arrived_exact");
        check(exact.direction().endsWith("same layer"), "same-layer stance must read same layer");
    }

    private static void landingProtectionFailureDowngradesToAnnotation() {
        // 到达事实成立 + 落地保护收场失败 → 注记降级，终态不再按失败处理。
        check(MoveToCompanionTask.landingProtectionUnverified(Map.of("complete", true, "failed", true)),
                "a finished-and-failed landing protection must set the unverified annotation");
        check(!MoveToCompanionTask.landingProtectionUnverified(Map.of("complete", true, "failed", false)),
                "a successful landing protection is not an annotation");
        check(!MoveToCompanionTask.landingProtectionUnverified(Map.of("failed", true)),
                "an unfinished landing protection must not claim a conclusion");
        check(!MoveToCompanionTask.landingProtectionUnverified(Map.of()),
                "no landing facts means no annotation");
    }

    /**
     * 两条成功话术同构：exact 与容差分支共用同一分级句格式「; arrival 等级 (距离 blocks 方向)」，
     * 只读消息文本的调用方一套匹配逻辑即可覆盖两种成功；剩余距离统一按 0.1 格取整。
     */
    private static void exactAndToleranceNotesShareOneShape() {
        ArrivalVerdict exact = ArrivalVerdict.of(358.5, 55.0, -74.5, 358, 55, -75, true);
        ArrivalVerdict tolerance = ArrivalVerdict.of(356.25, 55.0, -72.5, 358, 55, -75, true);
        String exactNote = MoveToCompanionTask.arrivalGradeNote(exact);
        String toleranceNote = MoveToCompanionTask.arrivalGradeNote(tolerance);
        check(exactNote.startsWith("; arrival " + ArrivalVerdict.EXACT + " ("),
                "exact arrival note must carry the arrived_exact grade word, got " + exactNote);
        check(toleranceNote.startsWith("; arrival " + ArrivalVerdict.WITHIN_TOLERANCE + " ("),
                "tolerance arrival note must carry the tolerance grade word, got " + toleranceNote);
        check(exactNote.contains(" blocks ") && toleranceNote.contains(" blocks "),
                "both arrival notes must carry a remaining distance in blocks");
        check(exactNote.endsWith(")") && toleranceNote.endsWith(")"),
                "both arrival notes must end with the direction clause");
        check(exactNote.contains("0.0 blocks "),
                "standing on the cell centre must report 0.0 remaining blocks, got " + exactNote);
        check(exactNote.endsWith("same layer)") && toleranceNote.endsWith("same layer)"),
                "same-layer arrivals must say so in both grades");
    }
}
