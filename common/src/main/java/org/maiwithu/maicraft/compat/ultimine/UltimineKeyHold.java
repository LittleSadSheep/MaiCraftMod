// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ultimine;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator.ChainShape;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 一次 FTB 连锁挖按着的连锁键，像玩家一样一步步来：准星对准触发格的那一面 → 按下连锁键（面板和白色轮廓跳出来）
 * → 当前形状不对就发 FTB 自己的切形状包切过去（面板上的形状名跟着变）→ 等服务器按这个形状发回选区
 * → 选区每一格都挖得、调用方都同意才连锁 → 停半秒让观众看清轮廓 → 挖的时候一直按着 → 挖完松开。
 *
 * <p>哪一步不行就松开键、等 FTB 按松开处理了再答只挖这一格：界面藏着（F1）、服务器没装、主播自己按着、
 * FTB 不让（冷却、饿着）、选区里有一格不该挖、工具耐久撑不完这一批、六秒等不到选区。
 * 施工的 3×3 在按键之前先看一眼命中面，注定连不成就不按键，免得面板闪一下。
 */
final class UltimineKeyHold implements ChainKeyHold {

    /** 从对准到能挖最多等几刻：按键、切形状、等选区都算在里面。 */
    static final int GIVE_UP_TICKS = 120;
    /** 选区到了以后至少画几刻再挖：观众要看得到连锁的白色轮廓。 */
    static final int VISIBLE_TICKS = 10;
    /** 发了切形状包后等几刻还没切过去，就再发一次。 */
    static final int CYCLE_WAIT_TICKS = 20;
    /** 切形状最多发几次：形状再多也转得到，转不到说明那边不认。 */
    static final int MAX_CYCLES = 8;
    /** 切过形状后，等新选区最多等几刻；同一刻收到新形状和新选区时不会再来新的，就用手上这一份。 */
    static final int SWITCH_SETTLE_TICKS = 6;
    /** 松开连锁键后等几刻：FTB 每刻开头才看键、发松键包，等它发出去再挖，下一下就不带连锁。 */
    static final int RELEASE_TICKS = 2;

    private final UltimineControls controls;
    private final ChainSite site;
    private final ChainShape shape;
    private final Predicate<BlockPos> mayBreak;

    private BlockPos origin;
    private Direction face;
    private String toolIdentity;
    private long openedAt;
    private boolean pressed;
    /** 按下或切完形状那一刻的选区序号：之后来的才是新算的。 */
    private long pressedRevision;
    private int cycles;
    private int cycledFrom = -1;
    private long cycledAt;
    /** 切过形状且已经看到切过去了：那一刻的刻数与选区序号。 */
    private long settledAt = -1;
    private long settledRevision = -1;
    private long admittedRevision = -1;
    private long admittedAt;
    /** 这次不连锁的原因；定下来以后先松键再答。 */
    private String singleWhy;
    private boolean releasing;
    private long releasedAt;

    UltimineKeyHold(UltimineControls controls, ChainSite site, ChainShape shape, Predicate<BlockPos> mayBreak) {
        this.controls = controls;
        this.site = site;
        this.shape = shape;
        this.mayBreak = mayBreak;
    }

    @Override public Readiness ready(PlayerContext context, BlockHitResult hit) {
        long now = site.tick(context);
        if (singleWhy != null) return single(now, singleWhy);
        // 第一次问时记下准星实际命中的格和面：形状按这一面算，之后准星换了就不连。
        if (origin == null) {
            origin = hit.getBlockPos().immutable();
            face = hit.getDirection();
            toolIdentity = site.tool(context).identity();
            openedAt = now;
        } else if (!origin.equals(hit.getBlockPos()) || face != hit.getDirection()) {
            return single(now, "准星换了格或换了面");
        }
        String tool = site.tool(context).identity();
        if (!tool.equals(toolIdentity)) {
            // 还没挥第一下：挖掘这时正在换顺手的工具（花岗岩换镐、换拆解机），按换好的那把重新认，
            // 选区按新工具重新核、重新给观众看。原先一换就不连，实机 10-13 往下挖台阶最下一行回回"手里的工具换了"。
            toolIdentity = tool;
            admittedRevision = -1;
        }
        if (controls.hudHidden()) return single(now, "界面被藏起来了（F1），观众看不到连锁的轮廓");
        if (!controls.serverListens()) return single(now, "服务器没装 FTB 连锁挖");
        if (!pressed && controls.heldByHand()) {
            return now - openedAt > GIVE_UP_TICKS ? single(now, "主播一直按着连锁键")
                    : new Readiness.Wait("主播按着连锁键，等他松开");
        }
        if (now - openedAt > GIVE_UP_TICKS) return single(now, "等了六秒没等到 FTB 按" + ChainShapes.shownName(shape) + "发回的选区");
        if (!pressed) return press(context, now);
        controls.holdKey(true);
        return switchedAndPreviewed(context, now);
    }

    // 按键之前：FTB 不让就不按；施工的 3×3 先看一眼命中面，连不成两格或有一格不该挖就不按。
    private Readiness press(PlayerContext context, long now) {
        Optional<String> refused = controls.refusal(site.player(context), origin);
        if (refused.isPresent()) return single(now, "FTB 现在不让连锁：" + refused.get());
        if (shape == ChainShape.FACE_SQUARE) {
            Optional<String> why = squareRefusal(context);
            if (why.isPresent()) return single(now, why.get());
        }
        controls.holdKey(true);
        pressed = true;
        pressedRevision = controls.selection().revision();
        return new Readiness.Wait("按下连锁键");
    }

    // 命中面 3×3 里有东西的格都要挖得、调用方都同意，而且至少两格才值得连。
    private Optional<String> squareRefusal(PlayerContext context) {
        int occupied = 0;
        for (BlockPos pos : ChainShapes.square(origin, face)) {
            Optional<ChainSite.Cell> cell = site.cell(context, pos);
            if (cell.isEmpty()) return Optional.of("命中面上 " + pos.toShortString() + " 没加载");
            if (cell.get().air()) continue;
            Optional<String> why = cellRefusal(pos, cell.get());
            if (why.isPresent()) return why;
            occupied++;
        }
        return occupied < 2 ? Optional.of("命中面上只剩这一格") : Optional.empty();
    }

    // 键按着：形状不对先切；切对了等按这个形状新算的选区，逐格核过、观众看清了才挖。
    private Readiness switchedAndPreviewed(PlayerContext context, long now) {
        List<String> shapes = controls.shapes();
        int wanted = shapes.indexOf(ChainShapes.ftbName(shape));
        if (wanted < 0) return single(now, "FTB 里没有" + ChainShapes.shownName(shape) + "这个形状");
        int current = controls.currentShape();
        if (current != wanted) {
            if (cycledFrom == current && now - cycledAt < CYCLE_WAIT_TICKS) {
                return new Readiness.Wait("等 FTB 切到" + ChainShapes.shownName(shape));
            }
            if (cycles >= MAX_CYCLES) return single(now, "切了 " + cycles + " 次形状还没切到" + ChainShapes.shownName(shape));
            int count = shapes.size();
            boolean next = Math.floorMod(wanted - current, count) <= Math.floorMod(current - wanted, count);
            controls.cycleShape(next);
            cycles++;
            cycledFrom = current;
            cycledAt = now;
            settledAt = -1;
            admittedRevision = -1;
            return new Readiness.Wait("切到" + ChainShapes.shownName(shape));
        }
        UltimineControls.Selection selection = controls.selection();
        if (cycles > 0 && settledAt < 0) {
            settledAt = now;
            settledRevision = selection.revision();
        }
        if (!fresh(selection, now)) return new Readiness.Wait("等 FTB 按" + ChainShapes.shownName(shape) + "发回选区");
        List<BlockPos> cells = selection.cells();
        // 服务器按它那边的准星算：还没对上这一格这一面时再等等（转头的包可能还在路上）。
        if (!ChainShapes.fits(shape, origin, face, cells)) return new Readiness.Wait("等 FTB 按准星算出选区");
        if (cells.size() >= controls.outlineLimit()) return single(now, "选区有 " + cells.size() + " 格，画不全");
        if (cells.size() < 2) return single(now, "这一下只连得到一格");
        for (BlockPos pos : cells) {
            Optional<ChainSite.Cell> cell = site.cell(context, pos);
            if (cell.isEmpty()) return single(now, "选区里 " + pos.toShortString() + " 没加载");
            Optional<String> why = cellRefusal(pos, cell.get());
            if (why.isPresent()) return single(now, why.get());
        }
        int durability = site.tool(context).durabilityLeft();
        if (durability <= cells.size()) {
            return single(now, "工具只剩 " + durability + " 点耐久，挖不完这一批 " + cells.size() + " 格");
        }
        if (admittedRevision != selection.revision()) {
            admittedRevision = selection.revision();
            admittedAt = now;
        }
        if (now - admittedAt < VISIBLE_TICKS) return new Readiness.Wait("让观众看清连锁的轮廓");
        return new Readiness.Chain(cells);
    }

    // 选区是不是按现在这个形状新算的：按下或切完以后才到的那一份；切完形状同一刻就到了的，稍等一会儿没有更新的也认。
    private boolean fresh(UltimineControls.Selection selection, long now) {
        if (cycles == 0) return selection.revision() > pressedRevision;
        if (selection.revision() > settledRevision) return true;
        return selection.revision() > pressedRevision && now - settledAt >= SWITCH_SETTLE_TICKS;
    }

    // 一格不能连锁挖的原因：空了、流体、设施、挖不动、手上工具挖不下来、调用方不同意。
    private Optional<String> cellRefusal(BlockPos pos, ChainSite.Cell cell) {
        String at = pos.toShortString();
        if (cell.air()) return Optional.of("选区里 " + at + " 已经空了");
        if (cell.fluid()) return Optional.of(at + " 泡着水或岩浆");
        if (cell.facility()) return Optional.of(at + " 是箱子、机器这类设施");
        if (cell.unbreakable()) return Optional.of(at + " 挖不动");
        if (!cell.harvestable()) return Optional.of("手上的工具挖不下 " + at);
        if (!mayBreak.test(pos)) return Optional.of(at + " 不在这次该挖的格里");
        return Optional.empty();
    }

    // 定下不连锁：按着的先松开，等 FTB 按松开处理了再答只挖这一格。
    private Readiness single(long now, String why) {
        singleWhy = why;
        if (pressed) {
            controls.holdKey(false);
            pressed = false;
            releasing = true;
            releasedAt = now;
        }
        if (releasing && now - releasedAt < RELEASE_TICKS) return new Readiness.Wait("等 FTB 松开连锁键");
        releasing = false;
        return new Readiness.Single(why);
    }

    @Override public boolean keep(PlayerContext context) {
        if (!pressed) return false;
        controls.holdKey(true);
        // 挖着的时候形状被换了（主播滚了一下）、手里换了东西：这一批不作数，调用方停手改挖一格。
        int wanted = controls.shapes().indexOf(ChainShapes.ftbName(shape));
        return controls.currentShape() == wanted && site.tool(context).identity().equals(toolIdentity);
    }

    @Override public boolean release(PlayerContext context) {
        long now = site.tick(context);
        if (pressed) {
            controls.holdKey(false);
            pressed = false;
            releasing = true;
            releasedAt = now;
        }
        if (!releasing) return true;
        if (now - releasedAt < RELEASE_TICKS) return false;
        releasing = false;
        return true;
    }
}
