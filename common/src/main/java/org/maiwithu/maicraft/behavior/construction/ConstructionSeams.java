// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 施工引擎的执行接缝：任务只经这些窄接口读工地、拿动作、记账，游戏对象全留在生产实现里，
 * 任务因此能用替身离线测试。
 */
public final class ConstructionSeams {

    private ConstructionSeams() {}

    /** 工地读数：这一刻的世界与角色，不猜没加载的格。 */
    public interface ReadsSite extends ReadsBlocks {
        /** 角色所在维度的 ID。 */
        String dimension();

        /** 角色脚下那一格。 */
        BlockPos feet();

        /** 身上这种物品有几件。 */
        int carried(String itemId);

        /** 创造模式不扣耗材。 */
        boolean creative();

        /** 这一格怎么都挖不动（基岩、世界边界之外）。 */
        boolean unbreakable(BlockPos pos);

        /** 身上最不值钱的整块建材，拿来垫临时方块；没有为空。 */
        Optional<String> temporaryMaterial();
    }

    /** 许可：角色自己挑的一格能不能挖、能不能放，全仓只有许可检查点回答。 */
    public interface Guards {
        Optional<Problem> allows(PermissionCheck.WorldAction action, BlockPos pos, String blockType);
    }

    /** 放置预测：从现在站的位置点 clicked 的 face 放这一格，原版会放成什么；算不出为空。 */
    public interface PlansPlacement {
        Optional<PlacementPrediction.Placement> predict(PlannedCell cell, BlockPos clicked, Direction face);

        /** 被点的方块自己有右键行为时要潜行再点。 */
        boolean requiresSneak(BlockPos clicked);
    }

    /** 出手：放、倒、舀、点一下；每个动作带它结束后的结论读口。 */
    public interface Clicks {
        /** 点 placement 里的那一面放这一格。 */
        Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak, PlacementConfirmation confirmation);

        /** 朝这一格倒桶。 */
        Click pour(PlannedCell cell, InteractionConfirmation confirmation);

        /** 用空桶舀这一格的源液体。 */
        Click scoop(BlockPos source, InteractionConfirmation confirmation);

        /** 右键一下这一格（开关门）。 */
        Click use(BlockPos block, InteractionConfirmation confirmation);

        /** 一次出手：逐刻推进的动作，以及它结束后的结论（没出手就失败时为 null）。 */
        record Click(Action action, Supplier<InteractionResult> result) {}
    }

    /** 备手：要放的东西换到主手。 */
    public interface HoldsItem {
        HoldPlan hold(String itemId);
    }

    /** 备手的打算。 */
    public sealed interface HoldPlan {
        /** 已经在手上。 */
        record Ready() implements HoldPlan {}

        /** 在身上，按这个动作分刻换到主手。 */
        record Move(Action action) implements HoldPlan {}

        /** 身上没有。 */
        record NotCarried() implements HoldPlan {}

        /** 换不过来，带原因。 */
        record Cannot(Problem problem) implements HoldPlan {}
    }

    /** 记账：工地与临时方块记进世界记忆，重启后从那里读回。 */
    public interface Ledger {
        /** 开工：把蓝图包围盒记成"这里在盖东西"。 */
        void siteStarted(Blueprint blueprint);

        /** 放下并确认了一块临时方块。 */
        void temporaryPlaced(BlockPos pos, String blockType, String purpose);

        /** 收回了一块。 */
        void temporaryRemoved(BlockPos pos);

        /** 还没收回的临时方块。 */
        List<BlockPos> temporaries();
    }

    /** 走到没加载的那一片，让它加载出来；给不出走法为空。 */
    public interface Travels {
        Optional<Action> toward(WorldPosition where, Permissions permissions);
    }
}
