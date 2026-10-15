// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 浇筑模板：在岩浆池岸边用一只桶把下界门框浇出来的那套手法的几何与顺序。
 *
 * <p>原点是门内左下格，正对着岩浆池；岸向（{@code shore}）指向岸上，角色站的站台铺在门后方。
 * 门框底行连同两侧底脚的四格（起手行）就是池面上的一行岩浆源：先用一块石头挡住门内左下格，
 * 在门内右下格倒水，水碰到的岩浆源当场成黑曜石；挖掉挡块让水流过去，底行与两侧底脚就齐了，再把水收回。
 * 之后在门右侧和顶部后面砌六块挡水的模具，从右侧列顶部后方倒水让水贴着门位流满，
 * 再从池里一桶一桶舀岩浆浇进框的每一格——岩浆碰水立刻成黑曜石。
 * 格位是在实机上一桶一桶跑通的那套，不凭空改几何；岸向换了整套手法跟着转。
 */
public record PortalCastingLayout(BlockPos origin, Direction shore) {

    /** 浇一扇门池里至少要连通着这么多格岩浆源：十格框、起手两格、几桶损耗。 */
    public static final int MIN_REMAINING_SOURCES = 15;
    /** 一圈门框要几格黑曜石：原版四角可有可无，十格就够点火。 */
    public static final int FRAME_CELLS = 10;
    /** 挡块一块、模具六块：浇一扇门自己要带的石料。 */
    public static final int STONES_FOR_MOLD = 7;
    /** 站台铺多宽（横向两侧各多留一格）、铺多深（后方落水列距门内回流坑只有两格，多留几格台面）。 */
    private static final int BANK_ACROSS_FROM = -2;
    private static final int BANK_ACROSS_TO = 3;
    private static final int BANK_DEPTH = 4;
    /** 站台上方留几格净空：身体与向上倒桶的视线。 */
    private static final int CLEARANCE_HEIGHT = 3;

    public PortalCastingLayout {
        origin = origin.immutable();
        if (shore == null || shore.getAxis() == Direction.Axis.Y) {
            throw new IllegalArgumentException("岸向只能是水平方向");
        }
    }

    /** 以一格池面岩浆为门内左下格，四个岸向各一套模板。 */
    public static List<PortalCastingLayout> around(BlockPos seed) {
        List<PortalCastingLayout> out = new ArrayList<>(4);
        for (Direction shore : Direction.Plane.HORIZONTAL) {
            out.add(new PortalCastingLayout(seed, shore));
        }
        return List.copyOf(out);
    }

    /** 门内横向第 {@code across} 格、竖向第 {@code up} 格、朝岸上第 {@code behind} 格；横向沿岸向的顺时针方向铺开。 */
    public BlockPos cell(int across, int up, int behind) {
        return origin.relative(shore.getClockWise(), across).above(up).relative(shore, behind);
    }

    /** 这套模板浇出来的门框：原点落在横向坐标更小的那格门内，与门框几何的约定一致。 */
    public NetherPortalFrame frame() {
        Direction across = shore.getClockWise();
        BlockPos lower = across.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? cell(1, 0, 0) : origin;
        return new NetherPortalFrame(lower, across.getAxis(), 2, 3);
    }

    /** 起手行：池面上门框底行连同两侧底脚的四格，每格都得是岩浆源（或上次已浇成的黑曜石）。 */
    public List<BlockPos> startRow() {
        return List.of(cell(-1, 0, 0), cell(0, 0, 0), cell(1, 0, 0), cell(2, 0, 0));
    }

    /** 挡块：起手先用一块石头挡住门内左下格的岩浆，水才能在旁边那格站住。 */
    public BlockPos placeholder() {
        return cell(0, 0, 0);
    }

    /** 起手水：倒在门内右下格，水碰到的岩浆源成黑曜石；挡块挖掉后水流过去补齐另一侧。 */
    public BlockPos initialWater() {
        return cell(1, 0, 0);
    }

    /** 浇框用水：倒在右侧列顶部后方，水贴着模具往下流满门位；不占最终要黑曜石的顶行。 */
    public BlockPos castingWater() {
        return cell(1, 3, 1);
    }

    /** 模具：右侧列后方三块、右上角一块、左侧与中间一格后方各一块，六块挡水的石头，浇完拆掉。 */
    public List<BlockPos> mold() {
        return List.of(cell(2, 1, 1), cell(2, 2, 1), cell(2, 3, 1), cell(2, 3, 0),
                cell(-1, 1, 1), cell(0, 1, 1));
    }

    /** 门框底行：池面下一层的两格，起手水流过去就成；池只有一格深时下面是石头，要先挖再浇。 */
    public List<BlockPos> bottom() {
        return List.of(cell(0, -1, 0), cell(1, -1, 0));
    }

    /**
     * 底行之外的八格框，按浇的顺序：两侧底脚（起手多半已成）、左右两列由低到高、
     * 顶行先靠模具的那格、再借刚成型的黑曜石侧面浇另一格。
     */
    public List<BlockPos> upperFrame() {
        return List.of(cell(-1, 0, 0), cell(2, 0, 0), cell(-1, 1, 0), cell(-1, 2, 0),
                cell(2, 1, 0), cell(2, 2, 0), cell(1, 3, 0), cell(0, 3, 0));
    }

    /** 站台：门后方与池面同高的一片台面，角色站在上面倒桶、舀桶；不坚实的格要填。 */
    public List<BlockPos> bank() {
        List<BlockPos> cells = new ArrayList<>();
        for (int behind = 1; behind <= BANK_DEPTH; behind++) {
            for (int across = BANK_ACROSS_FROM; across <= BANK_ACROSS_TO; across++) {
                cells.add(cell(across, 0, behind));
            }
        }
        return List.copyOf(cells);
    }

    /** 净空：站台上方三格留给身体与向上倒桶的视线；模具格不算，它们本来就要砌。 */
    public List<BlockPos> clearance() {
        Set<BlockPos> mold = Set.copyOf(mold());
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos floor : bank()) {
            for (int up = 1; up <= CLEARANCE_HEIGHT; up++) {
                BlockPos at = floor.above(up);
                if (!mold.contains(at)) {
                    cells.add(at);
                }
            }
        }
        return List.copyOf(cells);
    }

    /** 整地要清空的格：池面以上的框格与站台上方的净空，开浇前都得是空的。 */
    public List<BlockPos> cellsToClear() {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos at : upperFrame()) {
            if (at.getY() > origin.getY()) {
                cells.add(at);
            }
        }
        cells.addAll(clearance());
        return List.copyOf(cells);
    }

    /** 模板声明的全部格：门框、门内、模具、净空、站台。舀岩浆不从这里面舀，勘察也只看这些格。 */
    public Set<BlockPos> footprint() {
        Set<BlockPos> cells = new LinkedHashSet<>(frame().frame());
        cells.addAll(frame().interior());
        cells.addAll(mold());
        cells.addAll(clearance());
        cells.addAll(bank());
        return Set.copyOf(cells);
    }

    /**
     * 整地之后的浇筑顺序：挡块 → 起手水 → 挖挡块 → 浇底行两格 → 收起手水 → 砌模具 →
     * 浇框水 → 浇八格 → 收浇框水 → 等门内退水。执行时每步按现场状态跳过已成的。
     */
    public List<Step> steps() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Step.Kind.PLACE, placeholder()));
        steps.add(new Step(Step.Kind.POUR_WATER, initialWater()));
        steps.add(new Step(Step.Kind.DIG, placeholder()));
        for (BlockPos at : bottom()) {
            steps.add(new Step(Step.Kind.CAST, at));
        }
        steps.add(new Step(Step.Kind.SCOOP_WATER, initialWater()));
        steps.add(new Step(Step.Kind.MOLD, origin));
        steps.add(new Step(Step.Kind.POUR_WATER, castingWater()));
        for (BlockPos at : upperFrame()) {
            steps.add(new Step(Step.Kind.CAST, at));
        }
        steps.add(new Step(Step.Kind.SCOOP_WATER, castingWater()));
        steps.add(new Step(Step.Kind.DRAIN, origin));
        return List.copyOf(steps);
    }

    /** 起手要浇成黑曜石的四格：两侧底脚与底行；它们都成了，挡块、起手水那几步就不用再做。 */
    public List<BlockPos> startFrameCells() {
        return List.of(cell(-1, 0, 0), cell(2, 0, 0), cell(0, -1, 0), cell(1, -1, 0));
    }

    /** 起手行里的两格门内：开始是池面岩浆，起手做完是空的（或还有水）。 */
    public List<BlockPos> startInterior() {
        return List.of(cell(0, 0, 0), cell(1, 0, 0));
    }

    /** 浇筑的一步：做什么、对哪一格。 */
    public record Step(Kind kind, BlockPos cell) {

        /** 浇筑的几种动作。 */
        public enum Kind {
            /** 放挡块：门内左下格放一块石头。 */
            PLACE,
            /** 倒水：起手水或浇框水。 */
            POUR_WATER,
            /** 挖掉挡块，让水流过去。 */
            DIG,
            /** 浇一格：桶里没岩浆先去池里舀，面向这格倒，水碰到即成黑曜石。 */
            CAST,
            /** 把水收回桶里。 */
            SCOOP_WATER,
            /** 砌六块模具。 */
            MOLD,
            /** 等门内的流水退净。 */
            DRAIN
        }

        public Step {
            cell = cell.immutable();
        }
    }
}
