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
import org.maiwithu.maicraft.behavior.acquire.spi.SourceServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * ME 终端来源：从附近的 ME 终端取网络里已有的东西，途径 ae2。
 *
 * <p>报价时在角色周围已加载区块里找终端，问保护判断能不能取用（自己或自家人的才取用），
 * 按上次在终端里看到的网络存货挑一台：记得有货报明确数量，都没看过就去最近一台、报不知道有多少。
 * 网络里的东西一直在变，记下的数只当线索，到了终端跟前以现场为准。
 * 动手前再核对终端还在、还能取用，然后交给从终端取货的动作。网络能合成但现货不够时不下单。
 */
public final class Ae2TerminalSource implements ItemSource {

    /** 这条途径：via 取 ae2 就只从 ME 终端取。 */
    public static final AcquireVia VIA = new AcquireVia("ae2", "从附近的 ME 终端取网络里已有的（装了 AE2 才有）");

    /** 找终端的半径：与找箱子的一样，再远就不算顺手能拿。 */
    public static final int SEARCH_RADIUS_BLOCKS = 64;

    /** 走到终端前、点开、取、关上，大约几个动作：和开一只箱子差不多。 */
    private static final int ACTIONS_PER_VISIT = 4;

    private final Ae2Compat compat;
    private final SourceServices services;
    private final TerminalTakes takes;
    private final SeenNetworkStock seen;
    private final Supplier<Instant> clock;

    public Ae2TerminalSource(Ae2Compat compat, SourceServices services, TerminalTakes takes, SeenNetworkStock seen,
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
                    "这台终端这次还没开过，不知道网络里有什么", hint(unknown.candidate().terminal()));
            case TerminalChooser.None none -> new SourceQuote.Unavailable(source, none.reason());
        };
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return Optional.empty();
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
