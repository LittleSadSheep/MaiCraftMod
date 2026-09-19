// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

/** 先验菜单事务，再验原生报价和一次性日志，最后检查工作流边界；不以这些测试冒充实机附魔。 */
public final class EnchantRegressionSuite {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        EnchantIntentTest.main(args);
        // 重启后的请求去重依赖父任务先落盘，再等待单次消费日志，不能仅验证日志文件自身。
        EnchantDurableCheckpointTest.main(args);
        org.maiwithu.maicraft.client.actor.MenuButtonTransactionTest.main(args);
        org.maiwithu.maicraft.core.task.enchant.EnchantmentQuoteTest.main(args);
        org.maiwithu.maicraft.core.task.enchant.EnchantmentSubmissionJournalTest.main(args);
        org.maiwithu.maicraft.core.task.enchant.EnchantWorkflowGuardTest.main(args);
        // 总任务暂停时菜单仍可确认附魔；恢复后应保留当时的成品与费用，而非用后来拾取的经验重新归因。
        org.maiwithu.maicraft.core.task.enchant.EnchantTransactionPauseTest.main(args);
        System.out.println("EnchantRegressionSuite: passed");
    }
}
