// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.behavior.lighting.FrontLampSites;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 随行插火把的生产落地：眼前的灯位从第一人称现场挑，换手走背包界面的原生搬运，插火把是瞄准交互动作
 * （点支撑面，确认只看灯位那一格）。会开界面的方块不作灯座（转述放置潜行判断这同一份事实）；
 * 保护读不到按受保护对待，不出手。
 */
public final class LiveTorchMoves implements DarknessNeed.TorchMoves {

    private final Interactions interactions;
    private final MovesToMainhand toMainhand;
    private final Supplier<Protection> protection;
    private final Set<String> protectedLandmarks;

    public LiveTorchMoves(Interactions interactions, ClientMovesToMainhand toMainhand,
                          Supplier<Protection> protection, Set<String> protectedLandmarks) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.protection = protection;
        this.protectedLandmarks = protectedLandmarks == null ? Set.of() : protectedLandmarks;
    }

    @Override public Task placeTorch(TickContext context, Consumer<BlockPos> onAttempt) {
        Supplier<PlayerContext> now = () -> context.player();
        // 灯座那一格会不会右键开界面：箱子、机器、工作台不作灯座。读不到现场时按会开界面对待。
        Predicate<BlockPos> opensScreen = at -> {
            PlayerContext player = now.get();
            if (player == null || player.level() == null) return true;
            return PlacementPrediction.requiresSneak(player.level(), at, player.level().getBlockState(at));
        };
        // 保护这一问跟着世界走：世界现场还没建好时读不到，按受保护对待，眼前一个灯位都挑不出来。
        Predicate<BlockPos> guarded = at -> {
            Protection current = protection == null ? null : protection.get();
            PlayerContext player = now.get();
            if (current == null || player == null || player.level() == null) return true;
            return current.blockProtected(position(at, player), null, protectedLandmarks);
        };
        return new PlaceTorchTask(tick -> FirstPersonScene.of(tick.player()), toMainhand, opensScreen, guarded, onAttempt,
                spot -> placeClick(spot, now));
    }

    /** 对支撑面点一下：立式点顶面，贴墙点墙面；放下去的火把落在灯位那一格，确认只看那一格。 */
    private PlaceTorchTask.Click placeClick(FrontLampSites.LampSpot spot, Supplier<PlayerContext> now) {
        AimAndInteract aim = interactions.useBlockPart(spot.support(),
                PlacementSpots.faceBox(spot.support(), spot.supportFace()),
                new TorchPlaced(spot.lamp(), spot.state().getBlock()));
        return new PlaceTorchTask.Click(aim, aim::result);
    }

    private static WorldPosition position(BlockPos at, PlayerContext player) {
        return new WorldPosition(at.getX(), at.getY(), at.getZ(), player.level().dimension().location().toString());
    }

    /** 插火把的确认：灯位那一格变成火把就是插上了；空着时等服务端确认，变成别的样子如实说。 */
    private record TorchPlaced(BlockPos lamp, Block torch) implements InteractionConfirmation {

        @Override public boolean requiresBlockAcknowledgement() {
            return true;
        }

        @Override public Verdict observe(PlayerContext context) {
            if (!context.level().isLoaded(lamp)) return Verdict.PENDING;
            var live = context.level().getBlockState(lamp);
            if (live.getBlock() == torch) return Verdict.APPLIED;
            if (live.isAir()) return Verdict.PENDING;
            return Verdict.DIVERGED;
        }
    }
}
