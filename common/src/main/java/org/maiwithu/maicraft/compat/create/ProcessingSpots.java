// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;

/**
 * 机械动力机器的加工处与进出口，纯函数：只看方块状态与位置。
 * 压机与搅拌器干活的地方在正下方第二格（中间空一格让压头、搅拌头伸下去），机械手在朝向前方第二格，
 * 鼓风机朝向那一面吹、前方第一格放水岩浆或火，锯齿朝上时锯顶上的东西；置物台与工作盆自己就是放东西的地方。
 * 吃转动的面由方块自己说（哪几面伸着轴），这里只把它们记成"转动输入"。
 */
final class ProcessingSpots {

    private ProcessingSpots() {
    }

    /** 这台加工机器干活的那一格：压机、搅拌器正下方第二格，机械手朝向前方第二格，鼓风机朝向前方第一格；别的没有。 */
    static Optional<BlockPos> workCell(CreateMachineCatalog.Work work, BlockState state, BlockPos at) {
        return switch (work) {
            case PRESS, MIXER, SPOUT -> Optional.of(at.below(2));
            case DEPLOYER -> facing(state).map(face -> at.relative(face, 2));
            case FAN -> facing(state).map(at::relative);
            default -> Optional.empty();
        };
    }

    /** 鼓风机往前找置物台最多找这么多格：风吹得更远，但置物台一般就放在水、岩浆或火后面一两格。 */
    static final int FAN_DEPOT_REACH = 6;

    /**
     * 鼓风机风里的那台置物台：沿风向从前方第一格起往前找，遇到置物台就是它；遇到挡风的实心方块就停，
     * 风吹不过去。前方第一格多半是水、岩浆或火（加工用的），置物台在它后面。
     *
     * @param depotAt 这一格是不是置物台
     * @param blocks  这一格是不是挡风的实心方块
     */
    static Optional<BlockPos> fanDepot(BlockState state, BlockPos at, Predicate<BlockPos> depotAt,
            Predicate<BlockPos> blocks) {
        Optional<Direction> face = facing(state);
        if (face.isEmpty()) return Optional.empty();
        for (int step = 1; step <= FAN_DEPOT_REACH; step++) {
            BlockPos cell = at.relative(face.get(), step);
            if (depotAt.test(cell)) return Optional.of(cell);
            if (blocks.test(cell)) return Optional.empty();
        }
        return Optional.empty();
    }

    /** 这一格的进出口：吃转动的面，加上加工处或放东西的面。 */
    static List<ExchangePoint> of(CreateMachineCatalog.Work work, BlockState state, BlockPos at,
            Set<Direction> shaftFaces, boolean generator) {
        List<ExchangePoint> points = new ArrayList<>();
        for (Direction face : shaftFaces) {
            // 动力源的轴是往外给转动，其余是从这一面接转动（传动件两头都算进出）。
            ExchangePoint.Flow flow = generator ? ExchangePoint.Flow.OUTPUT : ExchangePoint.Flow.BOTH;
            points.add(new ExchangePoint(at, face, ExchangePoint.Medium.ROTATION, flow,
                    generator ? "从这一面往外给转动" : "从这一面接转动"));
        }
        switch (work) {
            case PRESS -> points.add(new ExchangePoint(at.below(2), null, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.BOTH, "加工处：正下方第二格放置物台或传送带（压成板），放工作盆是压块；中间那一格空着"));
            case MIXER -> points.add(new ExchangePoint(at.below(2), null, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.BOTH, "加工处：正下方第二格放工作盆，料扔进盆里，产物留在盆里；中间那一格空着"));
            case DEPLOYER -> workCell(work, state, at).ifPresent(cell -> points.add(new ExchangePoint(cell, null,
                    ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                    "加工处：朝向前方第二格；那里放置物台或传送带时，用手里的东西给上面的东西加工")));
            case FAN -> workCell(work, state, at).ifPresent(cell -> points.add(new ExchangePoint(cell, null,
                    ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                    "风朝这一面吹（反着转时往回吸）：前方放水是洗、放岩浆或火是烧、放灵魂火是缠魂，风里或风前置物台上的东西被加工")));
            case SAW -> points.add(new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.BOTH, "锯齿朝上时锯顶面上的东西：从顶上扔进去或用传送带送进来，产物从另一头出来"));
            case GRINDER -> points.add(new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.INPUT, "从顶上扔东西进去磨碎"));
            case DEPOT -> points.add(new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.BOTH, "台面：手持东西右键顶面放一整叠（先收回台上原有的），空手右键顶面收回"));
            case BASIN -> points.add(new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.ITEM,
                    ExchangePoint.Flow.BOTH, "盆口：东西扔进盆里，流体用桶倒进去；空手右键取出盆里的东西"));
            case SPOUT -> {
                points.add(new ExchangePoint(at.below(2), null, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                        "加工处：正下方第二格放置物台或传送带，上面的东西被注上流体；中间那一格空着"));
                points.add(new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.FLUID, ExchangePoint.Flow.INPUT,
                        "顶上接流体管，或手持装着流体的桶右键倒进去"));
            }
            case DRAIN -> {
                points.add(new ExchangePoint(at, Direction.UP, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                        "东西扔上去或从侧面送进来，倒出里面的流体；倒空的东西留在上面，空手右键取回"));
                points.add(new ExchangePoint(at, Direction.DOWN, ExchangePoint.Medium.FLUID, ExchangePoint.Flow.OUTPUT,
                        "倒出的流体从下面或侧面接管抽走，手持空桶右键也能装"));
            }
            case CRAFTER -> points.add(new ExchangePoint(at, null, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                    "几台连成一片，每台放配方的一格料（手持料右键它的正面），凑齐后一起做，产物从箭头指的那一台出"));
            case MOTION -> points.add(new ExchangePoint(at, null, ExchangePoint.Medium.ROTATION, ExchangePoint.Flow.INPUT,
                    "转起来（或通电）时把粘在它前面的一片方块推拉、转动、吊起；停下后落回方块"));
            case SCHEMATIC -> points.add(new ExchangePoint(at, null, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.INPUT,
                    "界面里放蓝图与材料；蓝图加农炮照蓝图一格格放方块"));
            default -> {
            }
        }
        return points;
    }

    // 机械手、鼓风机的朝向在方块状态的 facing 上；读不到就不知道加工处在哪。
    private static Optional<Direction> facing(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING)
                ? Optional.of(state.getValue(BlockStateProperties.FACING)) : Optional.empty();
    }
}
