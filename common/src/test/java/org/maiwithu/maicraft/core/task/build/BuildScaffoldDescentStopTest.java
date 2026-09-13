// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongSets;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.client.actor.NativeConfirmation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.act.BlockDigger;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;

/** 回放已提交的原生挖掘确认，验证暂停不会吞掉刚到的成功回执；不发送挖掘或移动动作。 */
public final class BuildScaffoldDescentStopTest {
    private static final BlockPos TARGET = new BlockPos(6, 1, 6);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            var count = new AtomicInteger(); var drive = drive(h, count);
            BlockDigger digger = (BlockDigger) field(drive, "digger").get(drive);
            LocalPlayerContext context = ClientRuntime.requireContext(h.player);
            var constructor = NativeActionReceipt.class.getDeclaredConstructor(NativeActionReceipt.Kind.class, LocalPlayerContext.class,
                    int.class, int.class, NativeConfirmation.class, BlockPos.class, Direction.class);
            constructor.setAccessible(true);
            var receipt = constructor.newInstance(NativeActionReceipt.Kind.BREAK_BLOCK, context, 40, 2,
                    (NativeConfirmation) current -> NativeConfirmation.Verdict.APPLIED, TARGET, Direction.UP);
            var install = context.actions().getClass().getDeclaredMethod("install", NativeActionReceipt.class); install.setAccessible(true);
            install.invoke(context.actions(), receipt);
            field(digger, "pos").set(digger, TARGET); field(digger, "receipt").set(digger, receipt);
            h.set(TARGET, Blocks.AIR.defaultBlockState());
            context.actions().poll(context, receipt);
            check(!receipt.terminal() && count.get() == 0, "第一刻应用观察不足以提前记一次拆除");
            // 第二刻恰好发生暂停，真实确认端口此时才完成稳定确认，仍须把这一格准确交回项目账。
            h.nextTick(); drive.stop(); drive.stop();
            check(receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED && count.get() == 1,
                    "暂停边界兑现确认一次，多次stop不能重复记账");
            check((boolean) drive.evidence().get("confirmed_removal") && h.blockUses() == 0 && h.itemUses() == 0,
                    "暂停只结算旧回执，不补发新的游戏动作");
        }
        try (var h = new InteractionWorldTestHarness()) {
            var count = new AtomicInteger(); var drive = drive(h, count);
            h.set(TARGET, Blocks.AIR.defaultBlockState()); drive.stop();
            check(count.get() == 0 && !(boolean) drive.evidence().get("confirmed_removal"), "外部空气不会被暂停路径当作自己挖成");
        }
        System.out.println("BuildScaffoldDescentStopTest: pending native confirmation is settled exactly once at interruption");
    }
    private static BuildScaffoldDescentDrive drive(InteractionWorldTestHarness h, AtomicInteger count) {
        h.set(TARGET, Blocks.DIRT.defaultBlockState());
        return new BuildScaffoldDescentDrive(h.player, TARGET, Map.of(TARGET, Blocks.DIRT.defaultBlockState()),
                at -> true, LongSets.emptySet(), PlayerNav.ContextProvider.DEFAULT, at -> count.incrementAndGet());
    }
    private static Field field(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
