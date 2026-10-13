// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator.ChainShape;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold.Readiness;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * FTB 连锁键：按下 → 形状不对先切 → 等按这个形状新算的选区 → 逐格核过、观众看清才连锁；
 * 哪一步不行先松键、等 FTB 处理了松键再答只挖这一格；施工 3×3 注定连不成时不按键。
 */
class UltimineKeyHoldTest {

    private static final List<String> SHAPES = List.of("ftbultimine:shapeless", "ftbultimine:small_tunnel",
            "ftbultimine:small_square", "ftbultimine:large_tunnel", "ftbultimine:mining_tunnel", "ftbultimine:escape_tunnel");
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    /** 替身 FTB：键、当前形状、选区都由测试摆；切形状记下往哪切，按 FTB 的顺序转一格。 */
    private static final class Ftb implements UltimineControls {
        boolean listens = true;
        boolean hand;
        boolean down;
        boolean hidden;
        int shape;
        final List<Boolean> cycles = new ArrayList<>();
        Selection selection = new Selection(0, List.of());
        Optional<String> refused = Optional.empty();

        void sends(List<BlockPos> cells) {
            selection = new Selection(selection.revision() + 1, cells);
        }

        @Override public boolean serverListens() { return listens; }
        @Override public boolean heldByHand() { return hand; }
        @Override public void holdKey(boolean pressed) { down = pressed; }
        @Override public List<String> shapes() { return SHAPES; }
        @Override public int currentShape() { return shape; }
        @Override public void cycleShape(boolean next) {
            cycles.add(next);
            shape = Math.floorMod(shape + (next ? 1 : -1), SHAPES.size());
        }
        @Override public Selection selection() { return selection; }
        @Override public int outlineLimit() { return 256; }
        @Override public Optional<String> refusal(LocalPlayer player, BlockPos pos) { return refused; }
        @Override public boolean hudHidden() { return hidden; }
    }

    /** 替身现场：没摆的格都是挖得下来的石头；手里一把镐。 */
    private static final class Site implements ChainSite {
        long now;
        final Map<BlockPos, Cell> cells = new HashMap<>();
        int durability = 100;

        @Override public Optional<Cell> cell(PlayerContext context, BlockPos pos) {
            return Optional.of(cells.getOrDefault(pos, new Cell(false, false, false, false, true)));
        }
        @Override public Tool tool(PlayerContext context) { return new Tool("minecraft:iron_pickaxe", durability); }
        @Override public long tick(PlayerContext context) { return now; }
        @Override public LocalPlayer player(PlayerContext context) { return null; }
    }

    private final Ftb ftb = new Ftb();
    private final Site site = new Site();

    private UltimineKeyHold hold(ChainShape shape, Predicate<BlockPos> mayBreak) {
        return new UltimineKeyHold(ftb, site, shape, mayBreak);
    }

    private static BlockHitResult hit(Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(ORIGIN), face, ORIGIN, false);
    }

    /** 每刻问一次，直到不再说等；返回最后的答复。 */
    private Readiness askUntilDecided(UltimineKeyHold hold, Direction face, int limit, Runnable eachTick) {
        Readiness readiness = null;
        for (int i = 0; i < limit; i++) {
            site.now++;
            eachTick.run();
            readiness = hold.ready(null, hit(face));
            if (!(readiness instanceof Readiness.Wait)) return readiness;
        }
        return readiness;
    }

    @Test
    void 形状对了_按下等新选区_观众看清才连锁_挖完松键要等两刻() {
        ftb.shape = SHAPES.indexOf("ftbultimine:small_square");
        UltimineKeyHold hold = hold(ChainShape.FACE_SQUARE, pos -> true);
        List<BlockPos> square = ChainShapes.square(ORIGIN, Direction.NORTH);
        site.now = 1;
        assertInstanceOf(Readiness.Wait.class, hold.ready(null, hit(Direction.NORTH)));
        assertTrue(ftb.down, "按下了连锁键");
        Readiness decided = askUntilDecided(hold, Direction.NORTH, 40, () -> {
            if (ftb.selection.revision() == 0) ftb.sends(square);
        });
        assertEquals(square, assertInstanceOf(Readiness.Chain.class, decided).batch());
        assertTrue(site.now >= 1 + UltimineKeyHold.VISIBLE_TICKS, "轮廓画了半秒才挖");
        assertTrue(hold.keep(null));
        site.now++;
        assertFalse(hold.release(null), "刚松开，FTB 还没处理");
        assertFalse(ftb.down, "键已经松开");
        site.now += UltimineKeyHold.RELEASE_TICKS;
        assertTrue(hold.release(null));
    }

    @Test
    void 形状不对先切_挑近的方向_切过去才认选区() {
        ftb.shape = 0;
        UltimineKeyHold hold = hold(ChainShape.DOWNWARD_LINE, pos -> true);
        List<BlockPos> line = List.of(ORIGIN, ORIGIN.south().below(), ORIGIN.south(2).below(2));
        Readiness decided = askUntilDecided(hold, Direction.NORTH, 80, () -> {
            // 服务器那边：形状切到采矿通道以后才按它发选区。
            if (ftb.shape == SHAPES.indexOf("ftbultimine:mining_tunnel") && ftb.selection.cells().isEmpty()) ftb.sends(line);
        });
        assertEquals(line, assertInstanceOf(Readiness.Chain.class, decided).batch());
        assertEquals(List.of(false, false), ftb.cycles, "从无形状往回转两格就到采矿通道，比往前转四格近");
    }

    @Test
    void 按旧形状算的选区不认_等新形状的() {
        ftb.shape = SHAPES.indexOf("ftbultimine:small_square");
        UltimineKeyHold hold = hold(ChainShape.STRAIGHT_LINE, pos -> true);
        List<BlockPos> stale = ChainShapes.square(ORIGIN, Direction.NORTH);
        List<BlockPos> line = List.of(ORIGIN, ORIGIN.south(), ORIGIN.south(2));
        int[] ticks = {0};
        Readiness decided = askUntilDecided(hold, Direction.NORTH, 80, () -> {
            ticks[0]++;
            // 切形状的包还在路上时先到了一份按旧方形算的选区，过几刻才来按小型通道算的。
            if (ticks[0] == 3) ftb.sends(stale);
            if (ticks[0] == 20) ftb.sends(line);
        });
        assertEquals(line, assertInstanceOf(Readiness.Chain.class, decided).batch());
    }

    @Test
    void 选区里有一格不该挖_先松键再答只挖这一格() {
        ftb.shape = 0;
        BlockPos foreign = ORIGIN.east();
        UltimineKeyHold hold = hold(ChainShape.CONNECTED, pos -> !pos.equals(foreign));
        Readiness decided = askUntilDecided(hold, Direction.UP, 40, () -> {
            if (ftb.selection.revision() == 0 && ftb.down) ftb.sends(List.of(ORIGIN, foreign));
        });
        Readiness.Single single = assertInstanceOf(Readiness.Single.class, decided);
        assertTrue(single.why().contains("不在这次该挖的格里"), single.why());
        assertFalse(ftb.down, "答之前已经松开连锁键");
    }

    @Test
    void 施工命中面只剩一格_不按键直接只挖这一格() {
        ftb.shape = SHAPES.indexOf("ftbultimine:small_square");
        for (BlockPos pos : ChainShapes.square(ORIGIN, Direction.NORTH)) {
            if (!pos.equals(ORIGIN)) site.cells.put(pos, new ChainSite.Cell(true, false, false, false, true));
        }
        UltimineKeyHold hold = hold(ChainShape.FACE_SQUARE, pos -> true);
        site.now = 1;
        Readiness readiness = hold.ready(null, hit(Direction.NORTH));
        assertTrue(assertInstanceOf(Readiness.Single.class, readiness).why().contains("只剩这一格"));
        assertFalse(ftb.down, "没按过键，面板不闪");
    }

    @Test
    void 主播按着连锁键_等他松开_一直不松就不连() {
        ftb.hand = true;
        UltimineKeyHold hold = hold(ChainShape.CONNECTED, pos -> true);
        Readiness decided = askUntilDecided(hold, Direction.UP, UltimineKeyHold.GIVE_UP_TICKS + 5, () -> {});
        assertTrue(assertInstanceOf(Readiness.Single.class, decided).why().contains("主播"));
        assertFalse(ftb.down, "不抢主播的键");
    }

    @Test
    void 界面藏起来或者服务器没装_都不按键() {
        ftb.hidden = true;
        site.now = 1;
        assertInstanceOf(Readiness.Single.class, hold(ChainShape.CONNECTED, pos -> true).ready(null, hit(Direction.UP)));
        ftb.hidden = false;
        ftb.listens = false;
        assertInstanceOf(Readiness.Single.class, hold(ChainShape.CONNECTED, pos -> true).ready(null, hit(Direction.UP)));
        assertFalse(ftb.down);
    }

    @Test
    void 工具耐久撑不完这一批_不连锁() {
        site.durability = 2;
        UltimineKeyHold hold = hold(ChainShape.CONNECTED, pos -> true);
        Readiness decided = askUntilDecided(hold, Direction.UP, 40, () -> {
            if (ftb.selection.revision() == 0 && ftb.down) ftb.sends(List.of(ORIGIN, ORIGIN.east(), ORIGIN.west()));
        });
        assertTrue(assertInstanceOf(Readiness.Single.class, decided).why().contains("耐久"));
    }

    @Test
    void 挖着的时候形状被换了_键算丢了() {
        UltimineKeyHold hold = hold(ChainShape.CONNECTED, pos -> true);
        Readiness decided = askUntilDecided(hold, Direction.UP, 40, () -> {
            if (ftb.selection.revision() == 0 && ftb.down) ftb.sends(List.of(ORIGIN, ORIGIN.east()));
        });
        assertInstanceOf(Readiness.Chain.class, decided);
        ftb.shape = 3;
        assertFalse(hold.keep(null));
    }
}
