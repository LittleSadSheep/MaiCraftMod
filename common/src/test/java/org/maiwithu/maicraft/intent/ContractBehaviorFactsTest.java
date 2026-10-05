// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * 契约文本中的反直觉行为事实防丢失：这些事实曾在批次修复中写入目录，
 * 一次契约整体重写把它们静默替换掉，实机才发现模型侧的坑原样存在。
 * 本回归锁住关键词，契约重写时必须在现行文风里保留同义事实才能通过。
 */
public final class ContractBehaviorFactsTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        String acquire = catalogText("maicraft:acquire_items");
        // mine 查询收敛窗口：深矿带从地表提交诚实耗尽，先下降或同次授权探矿。
        check(acquire.contains("25-30"), "acquire_items names the mine convergence depth window");
        check(acquire.contains("mined_out"), "acquire_items names the honest deep-band mined_out report");
        // 公平闸口径：find_block 视线命中不是公平闸来源，需挖开暴露面。
        check(acquire.contains("公平闸"), "acquire_items states the fair-gate criterion for mine sources");
        // 重力方块塌落：整柱下落，从堆顶向下作业。
        check(acquire.contains("整柱下落") && acquire.contains("堆顶"), "acquire_items warns about gravity-block column collapse");
        // hunt 远行警示：前沿搜索可能带离起点很远，夜间或残血致命。
        check(acquire.contains("带离起点很远"), "acquire_items warns that hunt frontier search travels far");
        String interact = catalogText("maicraft:interact");
        // 平地站位：跨高度差空点击产生带过期确认的 UNCERTAIN。
        check(interact.contains("平地站位"), "interact states the flat-ground standing fact");
        System.out.println("ContractBehaviorFactsTest OK: behavior facts present in acquire_items and interact contracts");
    }

    private static String catalogText(String ability) {
        return SemanticAbilityCatalog.describe(ability).toString();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("contract behavior fact lost: " + what);
    }
}
