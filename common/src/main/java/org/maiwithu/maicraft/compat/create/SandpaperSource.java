// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 砂纸打磨当拿东西的一条途径（via=sandpaper）：要玫瑰石英磨成的磨制玫瑰石英这类东西时，
 * 主手拿砂纸、副手拿要磨的那件，按住右键磨一会儿，磨好的进背包、砂纸掉一点耐久（一张磨八件）。
 *
 * <p>原料与砂纸缺了回到拿东西的引擎去拿；一件一件磨，每磨完一件看背包里产物多了才算数，
 * 磨了没出东西就停下如实交回，不硬按下去。拿多少最终以引擎在动作结束后重新清点为准。
 */
final class SandpaperSource implements ItemSource {

    static final AcquireVia VIA = new AcquireVia("sandpaper", "机械动力的砂纸打磨：主手砂纸、副手原料，按住右键磨");
    /** Create 给砂纸挂的标签：普通砂纸与红砂纸都算。 */
    static final String SANDPAPER_TAG = "create:sandpaper";
    /** 一张砂纸磨几件（Create 给砂纸的耐久）。 */
    static final int USES_PER_SANDPAPER = 8;
    /** 磨一件按住多久就该好了（刻）：Create 用 32 刻，留一点余量；到点没出东西就当这一下没成。 */
    private static final int HOLD_TICKS = 60;

    private final CreateCompat compat;
    private final PlayerServices services;

    SandpaperSource(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "砂纸打磨";
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        ClientLevel level = level();
        if (level == null) return new SourceQuote.Unavailable(describe(), "角色不在世界里");
        Optional<CreateTools.Polishing> recipe = recipeFor(request.wanted(), compat.polishing(level));
        if (recipe.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "砂纸磨不出 " + request.wanted().specifier());
        }
        return offerFor(recipe.get(), request.count());
    }

    /** 报价，纯函数：要磨几次、要几张砂纸写进风险，原料与砂纸缺了要另外去拿。 */
    static SourceQuote offerFor(CreateTools.Polishing recipe, int wanted) {
        int rounds = roundsFor(recipe, wanted);
        int papers = (rounds + USES_PER_SANDPAPER - 1) / USES_PER_SANDPAPER;
        String risk = "要 " + rounds + " 件 " + recipe.inputs().getFirst() + "（或同类原料）与 " + papers
                + " 张砂纸，身上没有的要先去拿；一件一件磨";
        // 每磨一件要换手、按住一下：按三个动作算，原料与砂纸的来回另算在它们自己的来源上。
        return new SourceQuote.Offer("砂纸打磨", rounds * recipe.resultCount(),
                new AcquisitionCost(0, 2 + rounds * 3), risk, recipe.inputs().getFirst());
    }

    /** 磨出要的件数要磨几次：一次出 resultCount 件。 */
    static int roundsFor(CreateTools.Polishing recipe, int wanted) {
        int each = Math.max(1, recipe.resultCount());
        return (wanted + each - 1) / each;
    }

    /** 磨出要的东西的那一条配方：按物品比；按标签要的，产物挂着这个标签也算。 */
    static Optional<CreateTools.Polishing> recipeFor(WantedItem wanted, List<CreateTools.Polishing> recipes) {
        for (CreateTools.Polishing recipe : recipes) {
            if (wanted.isTag() ? hasTag(recipe.result(), wanted.tagId()) : recipe.result().equals(wanted.itemId())) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        Optional<CreateTools.Polishing> recipe = recipeFor(request.wanted(), compat.polishing(level));
        if (recipe.isEmpty()) return Optional.empty();
        String input = offer.hint().isBlank() ? recipe.get().inputs().getFirst() : offer.hint();
        int rounds = roundsFor(recipe.get(), request.count());
        int papers = (rounds + USES_PER_SANDPAPER - 1) / USES_PER_SANDPAPER;
        Permissions permissions = context.permissions();
        // 备原料 → 备砂纸 → 每一件：原料放副手、砂纸换主手、按住右键直到背包里多出一份产物。
        List<StepsInOrder.Step> steps = new ArrayList<>();
        steps.add(new StepsInOrder.Step("拿 " + rounds + " 件 " + input, () -> services.needs().actionFor(
                new ItemRequest(WantedItem.ofItem(input), rounds, "砂纸打磨的原料"), permissions)));
        steps.add(new StepsInOrder.Step("拿 " + papers + " 张砂纸", () -> services.needs().actionFor(
                new ItemRequest(WantedItem.ofTag(SANDPAPER_TAG), papers, "砂纸打磨"), permissions)));
        for (int round = 1; round <= rounds; round++) {
            String which = "第 " + round + " 件";
            steps.add(new StepsInOrder.Step(which + "：原料放副手", () -> toOffhand(input)));
            steps.add(new StepsInOrder.Step(which + "：砂纸换到主手", this::sandpaperToHand));
            steps.add(new StepsInOrder.Step(which + "：按住右键磨", () -> polishOnce(recipe.get().result())));
        }
        return Optional.of(new StepsInOrder("砂纸打磨出 " + recipe.get().result(), steps));
    }

    private Action toOffhand(String input) {
        PlayerContext current = services.context().get();
        if (current != null && current.localPlayer() != null && idOf(current.localPlayer().getOffhandItem()).equals(input)) {
            return null;
        }
        return services.gear().wear(input, GearSlotName.OFFHAND).orElse(null);
    }

    private Action sandpaperToHand() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return null;
        if (isSandpaper(current.localPlayer().getMainHandItem())) return null;
        Inventory inventory = current.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isSandpaper(stack)) return services.toMainhand().moveToMainhand(idOf(stack)).orElse(null);
        }
        return null;
    }

    // 按住右键磨一件：背包里产物比按下前多了才算磨好；Create 磨完一件就停，下一件重新按。
    private Action polishOnce(String result) {
        int before = carried(result);
        return services.interactions().useHeldItem(InteractionHand.MAIN_HAND,
                context -> carried(result) > before ? InteractionConfirmation.Verdict.APPLIED
                        : InteractionConfirmation.Verdict.PENDING, HOLD_TICKS);
    }

    private int carried(String itemId) {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return 0;
        Inventory inventory = current.localPlayer().getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (idOf(stack).equals(itemId)) count += stack.getCount();
        }
        return count;
    }

    private boolean isSandpaper(ItemStack stack) {
        ResourceLocation tag = ResourceLocation.tryParse(SANDPAPER_TAG);
        return !stack.isEmpty() && tag != null && stack.is(TagKey.create(Registries.ITEM, tag));
    }

    private static boolean hasTag(String itemId, String tagId) {
        ResourceLocation item = ResourceLocation.tryParse(itemId);
        ResourceLocation tag = ResourceLocation.tryParse(tagId);
        if (item == null || tag == null) return false;
        return BuiltInRegistries.ITEM.getOptional(item)
                .map(found -> new ItemStack(found).is(TagKey.create(Registries.ITEM, tag))).orElse(false);
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
