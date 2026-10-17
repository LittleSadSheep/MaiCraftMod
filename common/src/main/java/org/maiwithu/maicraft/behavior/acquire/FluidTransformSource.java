// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransform;
import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.inventory.StepsAside;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 往水里扔料让它变（AE2 的转化）：像玩家一样备好料，站到一格静水旁边，把几样料扔进同一格，退开等它变，再捡回来。
 * 配方由联动模组交来（一份配方表一条途径），找水、扔、等、捡都在这里。
 *
 * <p>报价：配方的产物对得上要的东西，附近 48 格内有能扔料的静水才报；身上缺几件料就像合成一样把先去弄料的麻烦算进去。
 * 动手：缺的料经引擎去拿（许可原样传下去），再交给扔料那一串。
 */
public final class FluidTransformSource implements ItemSource {

    /** 找水的半径：再远就该走跨区出行。 */
    static final int SEARCH_RADIUS_BLOCKS = 48;

    private final FluidTransforms transforms;
    private final TransformSites sites;
    private final PlayerServices services;
    private final PicksUpDrops drops;
    private final Optional<StepsAside> stepsAside;

    public FluidTransformSource(FluidTransforms transforms, TransformSites sites, PlayerServices services,
            PicksUpDrops drops, Optional<StepsAside> stepsAside) {
        this.transforms = Objects.requireNonNull(transforms, "transforms");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.services = Objects.requireNonNull(services, "services");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.stepsAside = Objects.requireNonNull(stepsAside, "stepsAside");
    }

    @Override public String describe() {
        return "往水里扔料等它变";
    }

    @Override public AcquireVia via() {
        return transforms.via();
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        Optional<List<FluidTransform>> recipes = sites.recipes(transforms);
        if (recipes.isEmpty()) return new SourceQuote.Unavailable(describe(), "角色现在不在世界里");
        Optional<FluidTransformPlan.Plan> plan = plan(recipes.get(), request);
        if (plan.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "没有扔进水里能变出 " + request.wanted().specifier() + " 的配方");
        }
        BlockPos here = at(context.characterAt());
        TransformSites.Pools pools = sites.pools(here, Set.copyOf(plan.get().recipe().fluids()), SEARCH_RADIUS_BLOCKS);
        if (pools.cells().isEmpty()) {
            return pools.complete()
                    ? new SourceQuote.Unavailable(describe(), "半径 " + SEARCH_RADIUS_BLOCKS + " 格内没有能扔料的静水"
                            + "（要配方认的流体的源格、水不往外流、头顶空着）；挖一格坑倒一桶水就有了")
                    : new SourceQuote.NotYet(describe(), "附近的水还没扫完，这一刻说不准有没有");
        }
        BlockPos pool = pools.cells().getFirst();
        double distance = Math.sqrt(pool.distSqr(here));
        double shortfall = plan.get().missing() * RecipeSource.SHORTFALL_BLOCKS_PER_ITEM;
        int actions = plan.get().needs().size() * 2 + 4;
        return new SourceQuote.Offer(describe(), Math.min(request.count(), plan.get().yield()),
                new AcquisitionCost(distance, actions, shortfall),
                "往 " + pool.toShortString() + " 的水里扔 " + plan.get().sets() + " 份料，等它变好再捡回来",
                plan.get().recipe().id());
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        Optional<List<FluidTransform>> recipes = sites.recipes(transforms);
        if (recipes.isEmpty()) return Optional.empty();
        Optional<FluidTransformPlan.Plan> plan = plan(recipes.get(), request)
                .filter(found -> found.recipe().id().equals(offer.hint()));
        if (plan.isEmpty()) return Optional.empty();
        TransformSites.Pools pools = sites.pools(at(context.characterAt()), Set.copyOf(plan.get().recipe().fluids()),
                SEARCH_RADIUS_BLOCKS);
        if (pools.cells().isEmpty()) return Optional.empty();
        // 备料 → 扔料、等、捡：每样料要份数件，缺的经引擎去拿，身上够的引擎当场清点就算拿到。
        List<Action> steps = new ArrayList<>();
        plan.get().needs().forEach((item, count) -> steps.add(services.needs().actionFor(
                new ItemRequest(WantedItem.ofItem(item), count, "扔进水里做 " + plan.get().recipe().resultId()),
                context.permissions())));
        steps.add(new FluidTransformRun(plan.get(), pools.cells().getFirst(), context.permissions(), services, drops,
                stepsAside, context.records()));
        return Optional.of(new StepwiseActions("备好料往水里扔，等它变好再捡回来", steps.toArray(Action[]::new)));
    }

    // 挑配方：产物对得上要的东西的那几条里，缺料最少、一份出得多的。
    private Optional<FluidTransformPlan.Plan> plan(List<FluidTransform> recipes, ItemRequest request) {
        return FluidTransformPlan.choose(recipes,
                result -> request.wanted().matches(result, services.itemTags().tagsOf(result)), request.count(),
                item -> CarriedItems.matching(services.backpack(), null, WantedItem.ofItem(item), services.itemTags()));
    }

    private static BlockPos at(WorldPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
