// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 工具箱随身取物当拿东西的一条途径（via=toolbox）：附近放着的 Create 工具箱（服务器配置 toolboxRange，默认 10 格）
 * 不用走过去，照工具箱轮盘选一格那样把那一格挂到一个空着的快捷栏格上——工具箱给那格补到半组（工具就是一件）；
 * 人在工具箱附近时用掉会再补、多了会收回，离远了手上的留着。要的件数超过半组就多挂几格。
 * 依据 Create 6.0.11 的 ToolboxEquipPacket 与 ToolboxBlockEntity.tickPlayers。
 */
final class ToolboxSource implements ItemSource {

    static final AcquireVia VIA = new AcquireVia("toolbox",
            "附近 Create 工具箱里的东西：不用走过去，挂到快捷栏空格上（一格最多半组，人在附近时用掉会补）");
    private static final int HOTBAR = 9;
    /** 发了挂一格的那一包后，等快捷栏那一格出现东西最多这么多刻。 */
    private static final int LINK_WAIT_TICKS = 40;

    private final CreateCompat compat;
    private final PlayerServices services;

    ToolboxSource(CreateCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "附近的工具箱";
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        PlayerContext current = services.context().get();
        if (current == null || current.level() == null || current.localPlayer() == null) {
            return new SourceQuote.Unavailable(describe(), "角色不在世界里");
        }
        List<Link> links = plan(request, current.level(), current.localPlayer());
        if (links.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), freeHotbar(current.localPlayer()).isEmpty()
                    ? "快捷栏没有空格，挂不上" : "附近 " + compat.toolboxRange() + " 格内的工具箱里没有 " + request.wanted().specifier());
        }
        int total = links.stream().mapToInt(Link::count).sum();
        return new SourceQuote.Offer(describe(), total, new AcquisitionCost(0, links.size()),
                "挂在快捷栏上：人在工具箱附近时用掉会补、多了会收回");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        PlayerContext current = services.context().get();
        if (current == null || current.level() == null || current.localPlayer() == null) return Optional.empty();
        List<Link> links = plan(request, current.level(), current.localPlayer());
        if (links.isEmpty()) return Optional.empty();
        // 一格一格挂：发一包，等那一格出现东西再挂下一格。
        List<StepsInOrder.Step> steps = new ArrayList<>();
        for (Link link : links) {
            steps.add(new StepsInOrder.Step("把工具箱里的 " + link.itemId() + " 挂到快捷栏第 " + (link.hotbar() + 1) + " 格",
                    () -> new Linking(link)));
        }
        return Optional.of(new StepsInOrder("从附近的工具箱挂 " + request.wanted().specifier(), steps));
    }

    // 挑哪几格挂：东西多的那一格先挂，一格给半组，挂到够要的件数或快捷栏空格用完为止。
    private List<Link> plan(ItemRequest request, ClientLevel level, LocalPlayer player) {
        List<Integer> free = freeHotbar(player);
        List<Link> links = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (CreateTools.Toolbox box : compat.toolboxes(level, player)) {
            for (CreateTools.Compartment compartment : box.compartments()) {
                if (compartment.count() > 0 && matches(request.wanted(), compartment.itemId())) {
                    candidates.add(new Candidate(box.pos(), compartment));
                }
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> -candidate.compartment().count()));
        int wanted = request.count();
        int gathered = 0;
        for (Candidate candidate : candidates) {
            int left = candidate.compartment().count();
            while (left > 0 && gathered < wanted && !free.isEmpty()) {
                int each = Math.min(left, halfStack(candidate.compartment().maxStack()));
                links.add(new Link(candidate.box(), candidate.compartment().index(), free.removeFirst(),
                        candidate.compartment().itemId(), each));
                left -= each;
                gathered += each;
            }
        }
        return links;
    }

    /** 挂一格给多少件，纯函数：半组（上取整），工具这种一组一件的就是一件。 */
    static int halfStack(int maxStack) {
        return Math.max(1, (maxStack + 1) / 2);
    }

    private boolean matches(WantedItem wanted, String itemId) {
        return wanted.matches(itemId, wanted.isTag() ? services.itemTags().tagsOf(itemId) : Set.of());
    }

    private static List<Integer> freeHotbar(LocalPlayer player) {
        List<Integer> free = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < HOTBAR; slot++) {
            if (inventory.getItem(slot).isEmpty()) free.add(slot);
        }
        return free;
    }

    /** 一个能挂的来处：哪个工具箱的哪一格。 */
    private record Candidate(BlockPos box, CreateTools.Compartment compartment) {
    }

    /** 一次挂：哪个工具箱的第几格挂到快捷栏第几格，预计给几件。 */
    private record Link(BlockPos box, int compartment, int hotbar, String itemId, int count) {
    }

    /** 发一包把那一格挂上，等快捷栏那一格出现那样东西；等不到如实说，不再发第二包。 */
    private final class Linking implements Action {
        private final Link link;
        private boolean sent;
        private int waited;

        Linking(Link link) {
            this.link = link;
        }

        @Override public ActionStatus tick(TickContext context) {
            ItemStack now = context.player().localPlayer().getInventory().getItem(link.hotbar());
            if (sent && !now.isEmpty() && BuiltInRegistries.ITEM.getKey(now.getItem()).toString().equals(link.itemId())) {
                return ActionStatus.done();
            }
            if (!sent) {
                compat.equipFromToolbox(link.box(), link.compartment(), link.hotbar());
                sent = true;
                return ActionStatus.progressed();
            }
            if (++waited <= LINK_WAIT_TICKS) return ActionStatus.running();
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "工具箱没把 " + link.itemId()
                    + " 挂到快捷栏上：可能离工具箱太远，或那一格已经空了"));
        }

        @Override public String describe() {
            return "从工具箱挂 " + link.itemId();
        }
    }
}
