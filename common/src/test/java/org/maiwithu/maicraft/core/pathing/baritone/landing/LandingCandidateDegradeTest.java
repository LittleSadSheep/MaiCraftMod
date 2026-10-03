// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone.landing;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 放置射线连续失配时按候选顺序降级：只向后走、只接已有格或已携带材料，
 * 接触证据随旧方案作废；没有可换方案时保持原行为。
 */
public final class LandingCandidateDegradeTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        thresholdGuardsAndDegradesToCarriedAlternative();
        cursorNeverRevisitsAndSkipsUnavailable();
        System.out.println("LandingCandidateDegradeTest: passed");
    }

    private static void thresholdGuardsAndDegradesToCarriedAlternative() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var water = (LandingAssistPlan) field(LandingAssistSession.class, "plan").get(f.session);
        var cobweb = new LandingAssistPlan(LandingAssistPlan.Kind.COBWEB,
                BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO.below(), Direction.UP, false);
        field(LandingAssistSession.class, "automaticCandidates")
                .set(f.session, List.of(water, cobweb));
        f.player.inventory.setItem(1, new ItemStack(Items.COBWEB));
        Method degrade = degrade();

        // 阈值以下只累计，不动方案。
        setMismatch(f.session, 4);
        check(!(Boolean) degrade.invoke(f.session, f.context), "阈值以下不得降级");
        check(field(LandingAssistSession.class, "plan").get(f.session) == water, "阈值以下保持当前方案");

        // 达到阈值：换到已携带的蜘蛛网候选，接触证据与稳定计数随旧方案作废。
        setBoolean(f.session, "waterContactObserved", true);
        setInt(f.session, "stableTicks", 3);
        setMismatch(f.session, 5);
        check((Boolean) degrade.invoke(f.session, f.context), "连续失配达到预算后必须降级");
        check(field(LandingAssistSession.class, "plan").get(f.session) == cobweb, "降级必须换到下一候选");
        check("degraded_to_next_landing_candidate".equals(field(LandingAssistSession.class, "placementGate").get(f.session)),
                "降级后放置门标记必须写明原因");
        check(!((Boolean) field(LandingAssistSession.class, "waterContactObserved").get(f.session)),
                "旧方案的水流接触证据不得带进新方案");
        check((int) field(LandingAssistSession.class, "stableTicks").get(f.session) == 0, "稳定计数必须清零重验");
        check((int) field(LandingAssistSession.class, "landingChanges").get(f.session) == 1, "降级必须计入方案变更");
        var preparation = field(LandingAssistSession.class, "preparation").get(f.session);
        check(preparation != null, "已携带材料的候选必须重建手持准备");
    }

    private static void cursorNeverRevisitsAndSkipsUnavailable() throws Exception {
        var f = new WaterLandingReplayTest.Fixture(false);
        var water = (LandingAssistPlan) field(LandingAssistSession.class, "plan").get(f.session);
        // 蜘蛛网未携带、干草以已有格存在：降级应跳过蜘蛛网直接落到干草，且不再回访水。
        var cobweb = new LandingAssistPlan(LandingAssistPlan.Kind.COBWEB,
                BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO.below(), Direction.UP, false);
        var hay = new LandingAssistPlan(LandingAssistPlan.Kind.HAY,
                BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO.below(), Direction.UP, true);
        field(LandingAssistSession.class, "automaticCandidates")
                .set(f.session, List.of(water, cobweb, hay));
        Method degrade = degrade();

        setMismatch(f.session, 5);
        check((Boolean) degrade.invoke(f.session, f.context), "首个不可用候选应被跳过并降级");
        check(field(LandingAssistSession.class, "plan").get(f.session) == hay, "不可用候选之后必须选已有格的干草");
        check((int) field(LandingAssistSession.class, "degradeCursor").get(f.session) == 2,
                "降级游标必须指向新方案在候选列表中的位置");

        // 干草再失配：列表走完即保持原行为，不得回访已放弃的水。
        setMismatch(f.session, 5);
        check(!(Boolean) degrade.invoke(f.session, f.context), "候选耗尽后不得降级也不得回访旧方案");
        check(field(LandingAssistSession.class, "plan").get(f.session) == hay, "候选耗尽后保持当前方案");
    }

    private static Method degrade() throws Exception {
        var method = LandingAssistSession.class.getDeclaredMethod(
                "degradeAfterRepeatedMismatch", org.maiwithu.maicraft.client.actor.LocalPlayerContext.class);
        method.setAccessible(true);
        return method;
    }

    private static void setMismatch(Object session, int value) throws Exception {
        field(LandingAssistSession.class, "placementMismatchTicks").setInt(session, value);
    }

    private static void setInt(Object instance, String name, int value) throws Exception {
        field(instance.getClass(), name).setInt(instance, value);
    }

    private static void setBoolean(Object instance, String name, boolean value) throws Exception {
        field(instance.getClass(), name).setBoolean(instance, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
