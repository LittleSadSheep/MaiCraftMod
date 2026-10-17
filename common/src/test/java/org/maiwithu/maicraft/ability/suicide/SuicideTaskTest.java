// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Cell;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Gear;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Moves;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Player;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.Tick;
import org.maiwithu.maicraft.ability.suicide.SuicideFakes.World;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 寻死的离线场景：收护具 → 挑死法 → 走过去 → 接触 → 死过重生 → 按原件穿回；没死成先穿回再失败；
 * 暂停先穿回、恢复后重新收；造危险右键没效果不换格反复试；放弃的原因合并计数，结果长度有界。
 */
class SuicideTaskTest {

    private static final String HELMET = "minecraft:iron_helmet";
    private static final String CHESTPLATE = "minecraft:iron_chestplate";

    private final World world = new World();
    private final Player player = new Player();
    private final Gear gear = new Gear(player);
    private final Moves moves = new Moves(player, world);
    private long tick;

    @Test
    void 脱甲收图腾_走进岩浆_重生后按原件穿回() {
        player.wear(GearSlotName.HEAD, HELMET, "头盔原件");
        player.wear(GearSlotName.CHEST, CHESTPLATE, "胸甲原件");
        player.totems.add(GearSlotName.OFFHAND);
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        SuicideTask task = task(null, 120);

        run(task, 200, () -> moves.calls.contains("stepInto:lava"));
        assertTrue(gear.calls.containsAll(List.of("takeOff:head", "takeOff:chest", "takeOff:offhand")));
        assertTrue(player.worn.isEmpty() && player.totems.isEmpty(), "护甲与图腾都收进了背包");
        assertTrue(task.survivalNeedsOff(), "尝试期间关掉生存需求");

        // 死了：控制循环停摆期间任务不推进，重生后重生次数加一、人在床边。
        player.respawns = 1;
        player.stand(new BlockPos(20, 64, 20));
        TaskResult result = finished(run(task, 100, () -> false));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.summary().contains("重生") && result.summary().contains("都穿回了"), result.summary());
        assertEquals("头盔原件", player.worn.get(GearSlotName.HEAD).original());
        assertEquals("胸甲原件", player.worn.get(GearSlotName.CHEST).original());
        assertFalse(player.totems.contains(GearSlotName.OFFHAND), "图腾不放回手上");
        assertFalse(task.survivalNeedsOff(), "死过以后生存需求恢复");
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && change.what().equals(HELMET)), "取下记一条");
        assertTrue(result.changes().stream().anyMatch(change -> change.note().contains("穿回了 head")), "穿回记一条");
    }

    @Test
    void 背包里有同种的另一顶头盔_穿回的是原来那顶() {
        player.inventory.put(0, new SuicidePlayer.WornPiece(HELMET, "另一顶"));
        player.wear(GearSlotName.HEAD, HELMET, "原来那顶");
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        SuicideTask task = task(null, 120);
        run(task, 200, () -> moves.calls.contains("stepInto:lava"));

        player.respawns = 1;
        finished(run(task, 100, () -> false));
        assertEquals("原来那顶", player.worn.get(GearSlotName.HEAD).original());
        assertEquals("另一顶", player.inventory.get(0).original(), "另一顶还在背包里");
        assertTrue(gear.calls.contains("wearFrom:1:head"), gear.calls.toString());
    }

    @Test
    void 背包满脱不下就带着死_照样寻死() {
        player.wear(GearSlotName.HEAD, HELMET, "头盔");
        gear.full = true;
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        SuicideTask task = task(null, 120);
        run(task, 200, () -> moves.calls.contains("stepInto:lava"));
        assertTrue(moves.calls.contains("stepInto:lava"), "脱不下不耽误寻死");

        player.respawns = 1;
        TaskResult result = finished(run(task, 100, () -> false));
        assertEquals(TaskResult.Status.DONE, result.status());
        SuicideTask.SuicideDetails details = (SuicideTask.SuicideDetails) result.details();
        assertEquals(1, details.armorStillWorn().size(), details.toString());
        assertTrue(details.armorStillWorn().get(0).contains(HELMET));
    }

    @Test
    void 附近没有死法_先穿回护甲再按缺什么说清() {
        player.wear(GearSlotName.HEAD, HELMET, "头盔");
        SuicideTask task = task(null, 120);
        TaskResult result = finished(run(task, 500, () -> false));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
        assertTrue(result.problem().message().contains("身上没有打火石或火焰弹"), result.problem().message());
        assertEquals("头盔", player.worn.get(GearSlotName.HEAD).original(), "没死成也把头盔穿回去");
    }

    @Test
    void 摔下去没死就换下一处_都没了如实说() {
        world.dig(4, 0, 63, 30);
        SuicideTask task = task(null, 120);
        run(task, 200, () -> moves.calls.contains("stepInto:fall"));
        // 落到坑底还活着（比如下面其实有水，估算时没看到）。
        player.stand(new BlockPos(4, 30, 0));
        player.onGround = true;
        TaskResult result = finished(run(task, 200, () -> false));
        assertTrue(result.problem().message().contains("摔下去没死 1 处"), result.problem().message());
    }

    @Test
    void 二十秒没再掉血就放弃这一处() {
        world.put(new BlockPos(2, 64, 0), Cell.CACTUS);
        SuicideTask task = task(null, 120);
        TaskResult result = finished(run(task, 1000, () -> false));
        assertTrue(result.problem().message().contains("20 秒没再掉血 1 处"), result.problem().message());
    }

    @Test
    void 一直在掉血就一直站着() {
        world.put(new BlockPos(2, 64, 0), Cell.CACTUS);
        SuicideTask task = task(null, 120);
        TickResult result = run(task, 900, () -> {
            player.health -= 0.01F;
            return false;
        });
        assertTrue(result instanceof TickResult.Running, "掉着血不放弃");
        assertTrue(task.survivalNeedsOff());
    }

    @Test
    void 点火右键出去没等到确认_不换格反复试() {
        player.carried.add("minecraft:flint_and_steel");
        moves.makeFails = Problem.of(Problem.Kind.STUCK, "提交了交互但没能确认结果");
        SuicideTask task = task(null, 120);
        TaskResult result = finished(run(task, 500, () -> false));
        assertEquals(1, moves.calls.stream().filter(call -> call.startsWith("make:")).count(), moves.calls.toString());
        assertEquals(1, result.unconfirmed().size(), "右键出去没确认的记进 unconfirmed");
        assertTrue(result.problem().message().contains("右键出去了，没等到确认 1 处"), result.problem().message());
    }

    @Test
    void 一直瞄不准_每种死法只试一次不会无限循环() {
        player.carried.add("minecraft:flint_and_steel");
        player.carried.add(WayToDie.LAVA_BUCKET_ITEM);
        moves.makeFails = Problem.of(Problem.Kind.UNREACHABLE, "镜头一直转不到脚下");
        SuicideTask task = task(null, 120);
        TaskResult result = finished(run(task, 500, () -> false));
        assertEquals(2, moves.calls.stream().filter(call -> call.startsWith("make:")).count(), moves.calls.toString());
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.problem().message().contains("瞄不准脚下"), result.problem().message());
    }

    @Test
    void 原地点火_火灭了再点_同一格只记一次放火() {
        player.carried.add("minecraft:flint_and_steel");
        SuicideTask task = task(null, 120);
        run(task, 50, () -> world.fireAt(new BlockPos(0, 64, 0)));
        assertTrue(moves.calls.contains("standIn:0, 64, 0"), moves.calls.toString());
        // 火自己灭了：原地再点。
        world.put(new BlockPos(0, 64, 0), Cell.AIR);
        run(task, 20, () -> moves.calls.stream().filter(call -> call.equals("make:fire")).count() == 2);
        player.respawns = 1;
        TaskResult result = finished(run(task, 50, () -> false));
        assertEquals(1, result.changes().stream().filter(change -> change.kind() == Change.Kind.BLOCK_PLACED).count());
        assertEquals("fire", ((SuicideTask.SuicideDetails) result.details()).way());
    }

    @Test
    void 只有敌对生物时跟着它站着挨打() {
        world.mobs.add(new SuicideWorld.HostileMob(9, "minecraft:zombie", new Vec3(5.5, 64, 0.5)));
        SuicideTask task = task(null, 120);
        run(task, 100, () -> moves.calls.contains("keepNear:9"));
        assertTrue(moves.calls.contains("keepNear:9"), moves.calls.toString());
        assertFalse(moves.calls.stream().anyMatch(call -> call.startsWith("walkTo")), "怪会动，跟着走不先走到站位");
    }

    @Test
    void 暂停时先穿回护甲_恢复后重新收() {
        player.wear(GearSlotName.HEAD, HELMET, "头盔");
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        SuicideTask task = task(null, 120);
        run(task, 200, () -> moves.calls.contains("stepInto:lava"));

        assertTrue(task.stopSafely(), "身上有脱下的护甲，先穿回再停");
        assertFalse(task.survivalNeedsOff(), "要停下了，生存需求恢复");
        run(task, 50, task::stoppedSafely);
        assertTrue(task.stoppedSafely());
        assertEquals("头盔", player.worn.get(GearSlotName.HEAD).original());

        // 目标暂停后又恢复：接着寻死，重新脱。
        task.pause();
        moves.calls.clear();
        gear.calls.clear();
        run(task, 200, () -> moves.calls.contains("stepInto:lava"));
        assertTrue(gear.calls.contains("takeOff:head"), gear.calls.toString());
        assertTrue(task.survivalNeedsOff());
    }

    @Test
    void 收护具时就死了也算死成() {
        player.wear(GearSlotName.HEAD, HELMET, "头盔");
        SuicideTask task = task(null, 120);
        task.tick(new Tick(tick++));
        player.respawns = 1;
        TaskResult result = finished(run(task, 100, () -> false));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
    }

    @Test
    void 到时限没死成_先穿回再按卡住失败() {
        player.wear(GearSlotName.HEAD, HELMET, "头盔");
        world.put(new BlockPos(3, 64, 0), Cell.LAVA);
        SuicideTask task = task(null, 10);
        TaskResult result = finished(run(task, 400, () -> {
            player.health -= 0.01F;
            return false;
        }));
        assertEquals(Problem.Kind.STUCK, result.problem().kind());
        assertTrue(result.problem().message().contains("10 秒内没死成"));
        assertEquals("头盔", player.worn.get(GearSlotName.HEAD).original());
    }

    @Test
    void 三十处岩浆都走不到_结果按原因合并不会很长() {
        for (int x = -15; x < 15; x++) world.put(new BlockPos(x, 64, 6), Cell.LAVA);
        moves.walkFails = true;
        SuicideTask task = task(null, 120);
        TaskResult result = finished(run(task, 2000, () -> false));
        assertTrue(result.attempts().size() <= 2, result.attempts().toString());
        assertTrue(result.problem().message().length() < 300, result.problem().message());
        assertTrue(result.problem().message().contains("走不到"), result.problem().message());
    }

    @Test
    void 不许动方块也原地点火_结果写在哪点的() {
        // change_blocks=none（默认）时照样点火：寻死就是目标，放一格火是手段；在哪放了什么记进变化。
        player.carried.add("minecraft:flint_and_steel");
        SuicideTask task = new SuicideTask(new SuicideInput(null, 8, 120,
                Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null), "game_rule"),
                player, world, moves, gear);
        run(task, 50, () -> world.fireAt(new BlockPos(0, 64, 0)));
        assertTrue(moves.calls.contains("make:fire"), moves.calls.toString());
        player.respawns = 1;
        TaskResult result = finished(run(task, 50, () -> false));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_PLACED
                && "minecraft:fire".equals(change.what()) && change.note().contains("0, 64, 0")), result.changes().toString());
    }

    private SuicideTask task(WayToDie via, long seconds) {
        return new SuicideTask(new SuicideInput(via, 8, seconds, Permissions.DEFAULT, SuicideModule.BY_GAME_RULE),
                player, world, moves, gear);
    }

    private TickResult run(SuicideTask task, int ticks, BooleanSupplier until) {
        TickResult result = TickResult.RUNNING;
        for (int i = 0; i < ticks; i++) {
            result = task.tick(new Tick(tick++));
            if (result instanceof TickResult.Finished || until.getAsBoolean()) return result;
        }
        return result;
    }

    private static TaskResult finished(TickResult result) {
        assertTrue(result instanceof TickResult.Finished, "应该结束了：" + result);
        return ((TickResult.Finished) result).result();
    }
}
