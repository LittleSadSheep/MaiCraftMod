// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.OptionalInt;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.HandlesOwnLighting;
import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 太暗这项生存需求：在地下、屋里这类头顶被遮住的地方走到暗处、包里有火把时，在眼前够得着的墙或地上
 * 顺手插一支，插完回到主任务原地。和饿了吃一口是同一类事，只找两个动作之间的空当（LATER），不抢身体、
 * 不专门跑路；出行、探索这类沿路走的任务，走路途中落地站稳时就插（SOON），不等走完整趟。
 *
 * <p>露天不算太暗：露天的方块光处处是 0，白天也一样，按方块光算会每两格插一支；头顶见得到天
 * （天空光 8 以上）就不插，白天黑夜都不插。随行补光默认开着，所以只认地下与屋里这类真暗的地方。
 *
 * <p>节制：距上次出手不足 2 格不再出手，原生被拒也算出手过，不原地撒灯。
 * 缺火把就不插、不采购、不报错，主任务照常；临时任务没做成不牵连主任务。
 * 主任务自己管照明（照明的施工任务声明特征接口）时让位，不往验收范围里插野火把。
 */
public final class DarknessNeed implements SurvivalNeed {

    /** 出手节制：距上次出手不足两格不再出手。 */
    static final double RECENT_ATTEMPT_SQR = 4.0;

    /** 天空光到这么多就算头顶见得到天：露天、林下、洞口都在这之上，不算太暗。 */
    static final int OPEN_SKY_LIGHT = 8;

    /**
     * 暗处的处境：脚与眼实测方块光的最小值（读不到现场为空）、包里有没有火把、脚站在哪，
     * 以及脚与眼天空光的较大值（头顶见不见得到天；读不到为空，按头顶被遮住算，只看方块光）。
     */
    public record DarknessFacts(OptionalInt darkestLight, boolean torchCarried, BlockPos feet, OptionalInt skyLight) {}

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
        if (facts.skyLight().isPresent() && facts.skyLight().getAsInt() >= OPEN_SKY_LIGHT) {
            // 头顶见得到天：露天的方块光本来就是 0，不算太暗，不插也不记暗格。
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
        // 沿路走的任务：走路本身就是正事，等走完整趟才插就成了摸黑走完全程；落地站稳、停下安全时就插（SOON），
        // 打断规则保证在空中、正跳、正搭桥这类停下不安全的时候不插。
        if (currentTask instanceof TorchesAlongTheWay) {
            return Urgency.SOON;
        }
        // 其余任务两个动作之间才插得进来：打断规则保证 LATER 只在空当里落地，干活干到一半不会被打断。
        return Urgency.LATER;
    }

    @Override
    public Task createTask(TickContext context) {
        return moves.placeTorch(context, pos -> lastAttempt = pos);
    }
}
