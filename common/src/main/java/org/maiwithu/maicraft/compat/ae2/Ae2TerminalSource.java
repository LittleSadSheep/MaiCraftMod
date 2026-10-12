// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * ME 终端来源：从附近的 ME 终端取网络里已有的东西，途径 ae2。
 *
 * <p>报价时在角色周围已加载区块里找终端，问保护判断能不能取用（自己或自家人的才取用），
 * 按上次在终端里看到的网络存货挑一台：记得有货报明确数量；没有记得有货的就去看一眼，报不知道有多少，
 * 上次看不行（没连上网络、没有这样东西）的排在最后，旧记录只拿来排先后、不拿来拒绝。
 * 网络里的东西一直在变，记下的数只当线索，到了终端跟前以现场为准。
 * 动手前再核对终端还在、还能取用，然后交给从终端取货的动作。网络能合成但现货不够时不下单。
 *
 * <p>身上带着绑过访问点的无线终端时先用它：不用走路，拿到主手右键打开就是同一张网；
 * 超出访问点范围、没电时界面上写着原因，如实收场，引擎再去找附近装着的终端。
 */
public final class Ae2TerminalSource implements ItemSource {

    /** 这条途径：via 取 ae2 就只从 ME 终端取。 */
    public static final AcquireVia VIA = new AcquireVia("ae2",
            "从 ME 网络取：身上带着绑过的无线终端就直接拿出来开，不然去附近的 ME 终端；没现货但有样板就在网络里下单合成，等做好再取（装了 AE2 才有）");

    /** 找终端的半径：与找箱子的一样，再远就不算顺手能拿。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;

    /** 走到终端前、点开、取、关上，大约几个动作：和开一只箱子差不多。 */
    private static final int ACTIONS_PER_VISIT = 4;

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final TerminalTakes takes;
    private final SeenNetworkStock seen;
    private final Supplier<Instant> clock;

    public Ae2TerminalSource(Ae2Compat compat, PlayerServices services, TerminalTakes takes, SeenNetworkStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.takes = Objects.requireNonNull(takes, "takes");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String describe() {
        return "ME 终端";
    }

    @Override public AcquireVia via() {
        return VIA;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) {
            return new SourceQuote.Unavailable(describe(), "角色不在世界里，找不了终端");
        }
        // 身上的无线终端：带在身上就能开，比走去任何一台装着的终端都近，先报它。
        Optional<String> wireless = carriedWirelessTerminal(player);
        if (wireless.isPresent()) {
            return wirelessQuote(wireless.get(), request, clock.get());
        }
        ClientLevel level = player.level();
        String dimension = level.dimension().location().toString();
        int radius = context.radiusBlocks() == null ? SEARCH_RADIUS_BLOCKS : context.radiusBlocks();
        WorldPosition here = context.characterAt();
        Instant now = clock.get();
        List<TerminalChooser.Candidate> candidates = new ArrayList<>();
        for (Ae2Terminals.Terminal terminal : compat.terminalsNear(level,
                new BlockPos(here.x(), here.y(), here.z()), radius)) {
            candidates.add(new TerminalChooser.Candidate(terminal, distance(here, terminal),
                    usable(level, dimension, terminal, context.permissions()),
                    seen.lastSeen(dimension, terminal, now)));
        }
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        return quoteFrom(TerminalChooser.choose(candidates, wanted, request.wanted().describe(), radius),
                request, describe(), now);
    }

    /**
     * 把挑终端的结论写成报价：记得有货报明确数量（不超过这次要的），没看过报不知道有多少，都用不上报给不了。
     * 报价线索记着是哪一格哪一面的终端，动手时原样带回来。
     */
    static SourceQuote quoteFrom(TerminalChooser.Choice choice, ItemRequest request, String source, Instant now) {
        return switch (choice) {
            case TerminalChooser.Known known -> new SourceQuote.Offer(source,
                    (int) Math.min(known.count(), request.count()),
                    new AcquisitionCost(known.candidate().distance(), ACTIONS_PER_VISIT),
                    "记的是" + ago(known.candidate().seen().orElseThrow().seenAt(), now)
                            + "在终端里看到的数，到了以终端里为准",
                    hint(known.candidate().terminal()));
            case TerminalChooser.Unknown unknown -> new SourceQuote.Offer(source, SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(unknown.candidate().distance(), ACTIONS_PER_VISIT),
                    unknown.lastTime().map(lastTime -> lastTime + "，不知道现在好了没有，去看一眼")
                            .orElse("这台终端这次还没开过，不知道网络里有什么"),
                    hint(unknown.candidate().terminal()));
            case TerminalChooser.None none -> new SourceQuote.Unavailable(source, none.reason());
        };
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return Optional.empty();
        // 报的是身上的无线终端：动手前再看一眼它还在身上，就拿出来打开。
        if (offer.hint().startsWith(WIRELESS_HINT)) {
            String itemId = offer.hint().substring(WIRELESS_HINT.length());
            return carriedWirelessTerminal(player).filter(itemId::equals).flatMap(id -> takes.takeWireless(id, request));
        }
        ClientLevel level = player.level();
        String dimension = level.dimension().location().toString();
        TerminalAt at = TerminalAt.parse(offer.hint());
        // 到了动手时终端可能已经被拆、换了部件：重新找一遍那一格，找不到就忘掉它的记录，交回空让引擎换路。
        Optional<Ae2Terminals.Terminal> terminal = compat.terminalsNear(level, at.block(), 0).stream()
                .filter(found -> found.side() == at.side())
                .findFirst();
        if (terminal.isEmpty()) {
            seen.forget(dimension, at.block(), at.side());
            return Optional.empty();
        }
        // 报价之后才问清归属（例如刚知道是别人放的）：同样不用，交回空让引擎换路。
        if (!usable(level, dimension, terminal.get(), context.permissions())) {
            return Optional.empty();
        }
        return takes.take(terminal.get(), dimension, request, context.permissions());
    }

    /** 报价线索里标明"用身上的无线终端"的前缀，后面跟物品 ID。 */
    static final String WIRELESS_HINT = "wireless:";
    /** 拿出无线终端、右键、取、关上，大约几个动作：不用走路。 */
    private static final int ACTIONS_WITH_WIRELESS = 3;

    // 身上（主背包、快捷栏、副手）第一件绑过访问点的无线终端的物品 ID。
    private Optional<String> carriedWirelessTerminal(PlayerContext player) {
        var inventory = player.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && compat.linkedWirelessTerminal(stack)) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    /**
     * 身上无线终端的报价。像玩家一样先看一眼 AE：这次还没打开过就按够数报（打开看一眼只要一两秒，
     * 没有的话引擎划掉它再换路，比先去手搓一整条链省事）；记得有现货报现货的数；记得没现货但有样板，
     * 按够数报、到了在网络里下单；上次没连上（超出范围、没电）照样报，写明上次的情况；
     * 五分钟内看过、网络里既没有也没样板，就不再去开。
     */
    static SourceQuote wirelessQuote(String itemId, ItemRequest request, Optional<SeenNetworkStock.Sighting> lastTime,
            Predicate<String> wanted) {
        AcquisitionCost cost = new AcquisitionCost(0, ACTIONS_WITH_WIRELESS);
        // 来源名与装着的终端用同一个：引擎按来源名划掉做砸了的来源。原先写成"身上的无线终端"，
        // 网络里没有这样东西时开了一趟一件没拿到，引擎划掉的名字对不上，又来问价又去开，实机转了五分多钟。
        String source = "ME 终端";
        String hint = WIRELESS_HINT + itemId;
        if (lastTime.isEmpty()) {
            return new SourceQuote.Offer(source, request.count(), cost,
                    "用身上的无线终端看一眼，这次还没打开过，有没有、有多少以界面里为准", hint);
        }
        SeenNetworkStock.Sighting seen = lastTime.get();
        if (!seen.linked()) {
            return new SourceQuote.Offer(source, SourceQuote.Offer.UNKNOWN_COUNT, cost,
                    "上次打开时没连上网络（超出范围或没电），不知道现在好了没有，打开看一眼", hint);
        }
        if (seen.count(wanted) > 0) {
            return new SourceQuote.Offer(source, (int) Math.min(seen.count(wanted), request.count()), cost,
                    "用身上的无线终端取，记的是上次打开时看到的数，打开后以界面里为准", hint);
        }
        if (seen.craftable(wanted)) {
            return new SourceQuote.Offer(source, request.count(), cost,
                    "网络里没现货但有样板，用身上的无线终端下单合成，等做好再取", hint);
        }
        // 上次只看到了第一包存货（取到想要的就关了，后面的包还没到）：没看见不等于没有，打开再看一眼。
        // 原先照着第一包说"没有"，AE 里明明有木头，拿木板、箱子五分钟内一律说网络里没有。
        if (!seen.complete()) {
            return new SourceQuote.Offer(source, SourceQuote.Offer.UNKNOWN_COUNT, cost,
                    "上次打开时存货还没同步齐，没看到这样东西，打开再看一眼", hint);
        }
        return new SourceQuote.Unavailable(source, "身上的无线终端连着的网络里，上次打开时既没有这样东西也没有样板");
    }

    private SourceQuote wirelessQuote(String itemId, ItemRequest request, Instant now) {
        Predicate<String> wanted = id -> request.wanted().matches(id, services.itemTags().tagsOf(id));
        return wirelessQuote(itemId, request, seen.lastSeenWireless(now), wanted);
    }

    // 能不能取用：问保护判断里"存储能不能取用"那一条，自己或自家人放的才行，归属没问清的不用。
    private boolean usable(ClientLevel level, String dimension, Ae2Terminals.Terminal terminal,
            Permissions permissions) {
        BlockPos block = terminal.block();
        String blockType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block).getBlock()).toString();
        return services.protection().mayUseStorage(
                new WorldPosition(block.getX(), block.getY(), block.getZ(), dimension), blockType,
                permissions.protectedLandmarks());
    }

    private static double distance(WorldPosition here, Ae2Terminals.Terminal terminal) {
        var center = terminal.panel().getCenter();
        double dx = center.x - (here.x() + 0.5);
        double dy = center.y - (here.y() + 0.5);
        double dz = center.z - (here.z() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // 多久以前：给 LLM 看的粗略说法。
    private static String ago(Instant seenAt, Instant now) {
        long seconds = Math.max(0, Duration.between(seenAt, now).getSeconds());
        return seconds < 60 ? " " + seconds + " 秒前" : " " + seconds / 60 + " 分钟前";
    }

    // 报价线索的写法：引擎不解读，动手时原样带回来。
    private static String hint(Ae2Terminals.Terminal terminal) {
        BlockPos block = terminal.block();
        return block.getX() + "," + block.getY() + "," + block.getZ() + "," + terminal.side().getSerializedName();
    }

    /** 报价线索认的那一台终端：哪一格、哪一面。 */
    private record TerminalAt(BlockPos block, Direction side) {
        static TerminalAt parse(String hint) {
            String[] parts = hint.split(",");
            return new TerminalAt(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])), Direction.byName(parts[3].toLowerCase(Locale.ROOT)));
        }
    }
}
