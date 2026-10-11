// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.OptionalInt;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.HandlesOwnLighting;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 太暗这项生存需求：走到暗处、包里有火把时，在眼前够得着的墙或地上顺手插一支，插完回到主任务原地。
 * 和饿了吃一口是同一类事，只找两个动作之间的空当（LATER），不抢身体、不专门跑路。
 *
 * <p>节制：距上次出手不足 2 格不再出手，原生被拒也算出手过，不原地撒灯。
 * 缺火把就不插、不采购、不报错，主任务照常；临时任务没做成不牵连主任务。
 * 主任务自己管照明（照明的施工任务声明特征接口）时让位，不往验收范围里插野火把。
 */
public final class DarknessNeed implements SurvivalNeed {

    /** 出手节制：距上次出手不足两格不再出手。 */
    static final double RECENT_ATTEMPT_SQR = 4.0;

    /** 暗处的处境：脚与眼实测方块光的最小值（读不到现场为空）、包里有没有火把、脚站在哪。 */
    public record DarknessFacts(OptionalInt darkestLight, boolean torchCarried, BlockPos feet) {}

    /** 读暗处处境的接缝：亮度按只读亮度读取实测，生产实现转述游戏接口层，测试给固定值。 */
    @FunctionalInterface
    public interface ReadsDarkness {
        DarknessFacts read(TickContext context);
    }

    /** 随行插火把怎么落地：创建这次的临时任务；出手过（成不成都算）时回调记下位置。生产实现在本包。 */
    @FunctionalInterface
    public interface TorchMoves {
        Task placeTorch(TickContext context, Consumer<BlockPos> onAttempt);
    }

    private final ReadsDarkness reader;
    private final TorchMoves moves;
    private final FollowLighting followLighting;
    /** 上次出手的位置；原生被拒也算出手过。 */
    private BlockPos lastAttempt;

    public DarknessNeed(ReadsDarkness reader, TorchMoves moves, FollowLighting followLighting) {
        this.reader = reader;
        this.moves = moves;
        this.followLighting = followLighting;
    }

    @Override public String name() {
        return "太暗";
    }

    @Override
    public Urgency urgency(TickContext context) {
        return urgency(context, null);
    }

    @Override
    public Urgency urgency(TickContext context, Task currentTask) {
        // 照明的施工任务自己管灯：它在量光、选灯位、验收，往里插野火把只会搅局。
        if (currentTask instanceof HandlesOwnLighting) {
            return null;
        }
        if (!followLighting.enabled()) {
            return null;
        }
        DarknessFacts facts = reader.read(context);
        if (facts == null || facts.darkestLight().isEmpty()) {
            return null;
        }
        int darkest = facts.darkestLight().getAsInt();
        if (darkest >= followLighting.minimumLight()) {
            return null;
        }
        // 走过并实测仍暗的格记下来：light 能力查状态时报"最近的暗格"，卸载的格不猜。
        followLighting.noteDark(facts.feet(), darkest, context.gameTick());
        if (!facts.torchCarried()) {
            // 缺火把不插、不采购：这次临时任务都不开始，主任务照常。
            return null;
        }
        if (lastAttempt != null && facts.feet().distSqr(lastAttempt) < RECENT_ATTEMPT_SQR) {
            return null;
        }
        // 两个动作之间才插得进来：打断规则保证 LATER 只在空当里落地，干活干到一半不会被打断。
        return Urgency.LATER;
    }

    @Override
    public Task createTask(TickContext context) {
        return moves.placeTorch(context, pos -> lastAttempt = pos);
    }
}
