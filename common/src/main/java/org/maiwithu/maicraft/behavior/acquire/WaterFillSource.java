// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿空桶去水边装一桶水：像玩家缺水桶时，拿空桶走到最近的池塘、河边舀一桶。
 * 只舀"舀了会自己补回来"的水——两边都挨着水源、底下是实心或水的那一格（无限水），池塘、河、海都是；
 * 孤零零一两格的水（刷石机、机器里的、落地放的）不碰，舀了机器就停了。身上没有空桶先按同一套办法弄一只。
 */
public final class WaterFillSource implements ItemSource {

    /** 这条路的名字：拿空桶去水边装。 */
    public static final AcquireVia VIA = new AcquireVia("fill", "拿空桶去水边装一桶（只舀舀了会自己补回来的水）");
    /** 找水的半径：再远就该走跨区出行。 */
    public static final int SEARCH_RADIUS_BLOCKS = 48;
    private static final int WANT = 64;
    private static final int BUILD_BUDGET = 4096;
    private static final String WATER_BUCKET = "minecraft:water_bucket";
    private static final String BUCKET = "minecraft:bucket";

    private final PlayerServices services;
    private final ScanTargets registered;
    private boolean lastScanComplete = true;

    public WaterFillSource(PlayerServices services) {
        this.services = Objects.requireNonNull(services, "services");
        this.registered = new ScanTargets(services.blockScans());
    }

    @Override public String describe() {
        return "去水边装水";
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!request.wanted().matches(WATER_BUCKET, services.itemTags().tagsOf(WATER_BUCKET))) {
            return new SourceQuote.Unavailable(describe(), "水边只装得了水桶");
        }
        List<BlockPos> water = refillingWater(context.characterAt());
        if (water.isEmpty() && !lastScanComplete) {
            return new SourceQuote.NotYet(describe(), "附近的水还没扫完，这一刻说不准有没有");
        }
        if (water.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "半径 " + SEARCH_RADIUS_BLOCKS
                    + " 格内没有舀了会自己补回来的水（两边挨着水源的池塘、河、海）；孤零零一两格的水是机器或刚放的，不舀");
        }
        BlockPos nearest = water.getFirst();
        WorldPosition here = context.characterAt();
        double distance = Math.sqrt(nearest.distSqr(new BlockPos(here.x(), here.y(), here.z())));
        boolean hasBucket = CarriedItems.matching(services.backpack(), null, WantedItem.ofItem(BUCKET),
                services.itemTags()) > 0;
        return new SourceQuote.Offer(describe(), 1, new AcquisitionCost(distance, hasBucket ? 3 : 6),
                (hasBucket ? "拿身上的空桶" : "先弄一只空桶，再") + "去 " + nearest.toShortString() + " 舀一桶，一次一桶");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        List<BlockPos> water = refillingWater(context.characterAt());
        if (water.isEmpty()) return Optional.empty();
        Permissions permissions = context.permissions();
        List<Action> steps = new ArrayList<>();
        // 空桶 → 走到水边 → 空桶换到主手 → 对准那格水舀。
        if (CarriedItems.matching(services.backpack(), null, WantedItem.ofItem(BUCKET), services.itemTags()) == 0) {
            steps.add(services.needs().actionFor(new ItemRequest(WantedItem.ofItem(BUCKET), 1, "装水要的空桶"), permissions));
        }
        steps.add(new Scoop(water.getFirst(), permissions));
        return Optional.of(new StepwiseActions("去水边装一桶水", steps.toArray(Action[]::new)));
    }

    // 附近舀了会自己补回来的水源格，由近及远；角色不在世界里给空。
    private List<BlockPos> refillingWater(WorldPosition center) {
        PlayerContext player = services.context().get();
        ClientLevel level = player == null ? null : player.level();
        if (level == null) return List.of();
        Set<Block> targets = Set.of(Blocks.WATER);
        registered.ensure(level, targets);
        BlockPos at = new BlockPos(center.x(), center.y(), center.z());
        var result = services.blockScans().query(level, at, targets, WANT, SEARCH_RADIUS_BLOCKS / 16 + 1, BUILD_BUDGET,
                Set.of(), true);
        lastScanComplete = result.complete();
        List<BlockPos> hits = new ArrayList<>();
        for (BlockPos hit : result.hits()) {
            if (Math.sqrt(hit.distSqr(at)) <= SEARCH_RADIUS_BLOCKS && refills(level, hit)) hits.add(hit.immutable());
        }
        hits.sort(Comparator.comparingDouble(hit -> hit.distSqr(at)));
        return hits;
    }

    // 舀了会自己补回来：水平四邻里至少两格是水源，底下是实心方块或水源（原版无限水的规矩）；头顶露天好舀。
    static boolean refills(BlockGetter level, BlockPos pos) {
        if (!level.getFluidState(pos).isSourceOfType(Fluids.WATER)) return false;
        int sources = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (level.getFluidState(pos.relative(side)).isSourceOfType(Fluids.WATER)) sources++;
        }
        BlockPos below = pos.below();
        boolean floor = level.getFluidState(below).isSourceOfType(Fluids.WATER)
                || level.getBlockState(below).isSolidRender(level, below);
        return sources >= 2 && floor && level.getBlockState(pos.above()).isAir();
    }

    /** 走到水边、空桶换到主手、对准那格水舀上来：手里变成水桶才算装上。 */
    private final class Scoop implements Action {
        private enum Stage { APPROACH, HAND, SCOOP }

        private final BlockPos water;
        private final Permissions permissions;
        private Stage stage = Stage.APPROACH;
        private Action current;

        Scoop(BlockPos water, Permissions permissions) {
            this.water = water;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (current == null) {
                current = next(context.player());
                if (current == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有空桶，舀不了水"));
                }
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Failed failed) {
                return ActionStatus.failed(Problem.of(failed.problem().kind(),
                        "去 " + water.toShortString() + " 舀水没做成：" + failed.problem().message(), failed.problem().suggestion()));
            }
            if (!(status instanceof ActionStatus.Done)) return status;
            current.close();
            current = null;
            if (stage == Stage.SCOOP) return ActionStatus.done();
            stage = stage == Stage.APPROACH ? Stage.HAND : Stage.SCOOP;
            return ActionStatus.progressed();
        }

        // 按阶段建下一个动作：空桶已在主手就跳过换手；舀的确认是主手变成水桶。
        private Action next(PlayerContext player) {
            return switch (stage) {
                case APPROACH -> services.approach().toward(ApproachTarget.ofBlock(water), permissions);
                case HAND -> {
                    if (player != null && player.localPlayer() != null
                            && player.localPlayer().getMainHandItem().is(Items.BUCKET)) {
                        stage = Stage.SCOOP;
                        yield next(player);
                    }
                    yield services.toMainhand().moveToMainhand(BUCKET).orElse(null);
                }
                case SCOOP -> services.interactions().useHeldItemToward(water, InteractionHand.MAIN_HAND,
                        c -> c.localPlayer().getMainHandItem().is(Items.WATER_BUCKET)
                                ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING);
            };
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) current.close();
            current = null;
        }

        @Override public Interruptibility interruptibility() {
            return current == null ? Interruptibility.WORKING : current.interruptibility();
        }

        @Override public String describe() {
            return switch (stage) {
                case APPROACH -> "走到 " + water.toShortString() + " 的水边";
                case HAND -> "空桶换到主手";
                case SCOOP -> "舀一桶水";
            };
        }
    }
}
