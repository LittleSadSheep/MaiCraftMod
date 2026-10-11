// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
            case PRESS, MIXER -> Optional.of(at.below(2));
            case DEPLOYER -> facing(state).map(face -> at.relative(face, 2));
            case FAN -> facing(state).map(at::relative);
            default -> Optional.empty();
        };
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
