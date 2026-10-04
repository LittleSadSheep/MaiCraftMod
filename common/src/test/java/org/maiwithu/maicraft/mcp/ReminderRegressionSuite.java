// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.client.actor.AcuteSurvivalRemindersTest;
import org.maiwithu.maicraft.client.actor.GameplayRemindersTest;
import org.maiwithu.maicraft.client.actor.NativeRestStatisticsTest;
import org.maiwithu.maicraft.client.actor.SurvivalRemindersTest;
import org.maiwithu.maicraft.client.runtime.BurningExposureReminderTest;
import org.maiwithu.maicraft.client.runtime.CombatEquipmentReminderTest;
import org.maiwithu.maicraft.client.runtime.DrowningReminderTest;
import org.maiwithu.maicraft.client.runtime.FoodSupplyReminderTest;
import org.maiwithu.maicraft.client.runtime.GearDurabilityReminderTest;
import org.maiwithu.maicraft.client.runtime.InventorySpaceReminderTest;
import org.maiwithu.maicraft.client.runtime.LowLightCombatReminderTest;
import org.maiwithu.maicraft.client.runtime.SleepReminderTest;
import org.maiwithu.maicraft.intent.ReminderBoardTest;

/** 独立验证生活提醒的证据、解除和多条投递；新增规则不必先跑与提醒无关的工具目录预算检查。 */
public final class ReminderRegressionSuite {
    public static void main(String[] args) throws Exception {
        ReminderBoardTest.main(args);
        LowLightCombatReminderTest.main(args);
        FoodSupplyReminderTest.main(args);
        CombatEquipmentReminderTest.main(args);
        SleepReminderTest.main(args);
        GearDurabilityReminderTest.main(args);
        InventorySpaceReminderTest.main(args);
        DrowningReminderTest.main(args);
        BurningExposureReminderTest.main(args);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        GameplayRemindersTest.main(args);
        SurvivalRemindersTest.main(args);
        // 溺水、着火、耐久与背包复用真实身体同步与随身物品观察，四条与旧提醒互不覆盖。
        AcuteSurvivalRemindersTest.main(args);
        NativeRestStatisticsTest.main(args);
        ReminderHttpTest.main(args);
        System.out.println("ReminderRegressionSuite: passed");
    }
}
