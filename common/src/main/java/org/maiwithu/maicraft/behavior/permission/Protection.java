// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 保护判断：全仓唯一回答"这一格方块、这只生物能不能碰"的地方。
 *
 * <p>受保护的范围是几路来源的并集：服务端的方块归属记录、记住的区域、这次任务额外保护的地标、
 * 推断为玩家放置的方块，以及有名字、被驯服、拴着绳、围栏里的生物。任何一路说受保护就是受保护；
 * 哪一路都说不清时，按受保护处理——少动一格，好过拆了别人盖的。
 *
 * <p>这里只判断不动手：读的是只读接缝，给的是结论，纯函数可测。
 * 角色自己放的方块不算受保护（临时方块用完要收回），判据是归属记录里的编号就是角色自己。
 *
 * <p>存储另有一问（{@link #mayUseStorage}）：箱子、AE 网络这类拿来取用、存放的东西，
 * 自家人（所有者在实例配置里列的信任玩家）放的也能用；拆、改仍按 {@link #blockProtected} 当别人的。
 */
public final class Protection {

    /** 地标保护的半径：地标是一个点，点周围的这片都算它的地盘。 */
    static final double LANDMARK_RADIUS = 8.0;
    /** 记住的"家"叫这个名字。 */
    static final String HOME = "家";
    /** "家"的范围：离记住的家这么近就算家里。玩家常识。 */
    public static final double HOME_RADIUS_BLOCKS = 8.0;

    private final ReadsBlockOwnership ownership;
    private final RemembersRegions regions;
    private final ReadsRememberedPlaces places;
    private final GuessesPlayerMade guesses;
    private final String selfPlayerId;
    private final TrustedPlayers trusted;

    /**
     * @param ownership     方块归属的只读接缝（服务端记录）
     * @param regions       记住区域的只读接缝（世界记忆）
     * @param places        按名字读记过地点的只读接缝，用来定位额外保护的地标
     * @param guesses       玩家放置推断；还没接上时传 {@link GuessesPlayerMade#NOTHING}
     * @param selfPlayerId  角色自己的玩家编号；自己放的临时方块要能收回
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions,
            ReadsRememberedPlaces places, GuessesPlayerMade guesses, String selfPlayerId) {
        this(ownership, regions, places, guesses, selfPlayerId, TrustedPlayers.NOBODY);
    }

    /**
     * @param trusted 自家人：他们放的存储可以取用、存放；没配置时传 {@link TrustedPlayers#NOBODY}
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions,
            ReadsRememberedPlaces places, GuessesPlayerMade guesses, String selfPlayerId, TrustedPlayers trusted) {
        this.ownership = ownership;
        this.regions = regions;
        this.places = places;
        this.guesses = guesses;
        this.selfPlayerId = selfPlayerId;
        this.trusted = Objects.requireNonNull(trusted, "trusted");
    }

    /**
     * 一格方块受不受保护。
     *
     * <p>判断顺序：归属还拿不准（这一格所在的区块还没问过服务端）就按受保护处理；
     * 再看服务端归属记录（以记录为准，角色自己放的不算），再看记住的区域，
     * 再看额外保护的地标，最后靠玩家放置推断兜底。哪一路命中就不再往下问。
     *
     * @param position           方块位置
     * @param blockType          方块类型，例如 minecraft:chest；不知道传 null，推断这路就跳过
     * @param protectedLandmarks 这次任务额外保护的地标名
     */
    public boolean blockProtected(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        return blockProtected(position, protectedLandmarks, () -> guesses.likelyPlayerMade(position, blockType));
    }

    /**
     * 一格方块受不受保护，推断那一路由调用方自己给：寻路在后台线程上算路，读不了当刻现场，
     * 只能拿它自己读到的那份方块来猜。归属记录、记住的区域、地标几路与 {@link #blockProtected} 一样，
     * 记录说是角色自己放的照样不保护。
     *
     * @param seenPlayerMade 调用方看到的这一格像不像玩家放的
     */
    public boolean blockProtectedAsSeen(WorldPosition position, Set<String> protectedLandmarks,
            boolean seenPlayerMade) {
        return blockProtected(position, protectedLandmarks, () -> seenPlayerMade);
    }

    private boolean blockProtected(WorldPosition position, Set<String> protectedLandmarks,
            BooleanSupplier likelyPlayerMade) {
        // 拿不准就按受保护处理：归属还没问到，宁可少动一格。
        if (!ownership.known(position.dimension(), position.x(), position.y(), position.z())) return true;
        Optional<ReadsBlockOwnership.PlacedBy> placedBy =
                ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z());
        // 归属记录查得到就以它为准：别人放的受保护，自己放的临时方块不算。
        if (placedBy.isPresent()) return !placedBy.get().playerId().equals(selfPlayerId);
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return true;
        }
        if (inProtectedLandmark(position, protectedLandmarks)) return true;
        // 记录与区域都说不清，才靠猜；猜像就当是。前几路都没命中才去猜，现场读数只在这时才读。
        return likelyPlayerMade.getAsBoolean();
    }

    /**
     * 一只箱子、一台终端这类存储，能不能拿来取用、存放（不是拆）。
     *
     * <p>这次任务额外保护的地标旁边的不用；归属还没问清的不用（拿不准就不用）。
     * 有归属记录的看是谁放的：角色自己或自家人放的能用，别人放的不用。
     * 没有记录的：在记住的区域里、或推断像是玩家放的，不知道是谁的，按别人的算；
     * 野外、村庄、遗迹里不属于任何玩家的能用。
     *
     * @param position           存储方块的位置
     * @param blockType          方块类型，例如 minecraft:chest；不知道传 null，推断这路就跳过
     * @param protectedLandmarks 这次任务额外保护的地标名
     */
    public boolean mayUseStorage(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        if (inProtectedLandmark(position, protectedLandmarks)) return false;
        if (!ownership.known(position.dimension(), position.x(), position.y(), position.z())) return false;
        Optional<ReadsBlockOwnership.PlacedBy> placedBy =
                ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z());
        if (placedBy.isPresent()) {
            String owner = placedBy.get().playerId();
            return owner.equals(selfPlayerId) || trusted.includes(owner);
        }
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return false;
        }
        return !guesses.likelyPlayerMade(position, blockType);
    }

    /** 一只生物受不受保护：有名字、被驯服、拴着绳、围栏里，占上一条就是别人的，不碰。 */
    public boolean creatureProtected(ReadsCreatureSituation.CreatureSituation situation) {
        return situation.bondedToSomeone();
    }

    /** 这一格是不是角色自己放的（临时方块）：只有这个判断，TEMPORARY 档也放行收回。 */
    public boolean selfPlaced(WorldPosition position) {
        return ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z())
                .map(placedBy -> placedBy.playerId().equals(selfPlayerId))
                .orElse(false);
    }

    /**
     * 这一格的归属问到了没有：没问到时记下要问，过一两刻再来看。收回自己放下的东西前先等它问到，
     * 拿不准是不是自己放的就不动。
     */
    public boolean ownershipKnown(WorldPosition position) {
        return ownership.known(position.dimension(), position.x(), position.y(), position.z());
    }

    /**
     * 这一格是不是在记住的"家"附近：家记过、离它不到 {@link #HOME_RADIUS_BLOCKS} 格就算。
     * 家里的床是重生点，家里的台子、炉子是摆好用的，收回自己放下的东西时不拆这些。
     */
    public boolean insideHome(WorldPosition position) {
        Optional<WorldPosition> home = places.place(HOME);
        if (home.isEmpty()) return false;
        double dx = home.get().x() - position.x();
        double dy = home.get().y() - position.y();
        double dz = home.get().z() - position.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= HOME_RADIUS_BLOCKS;
    }

    /** 按名字找地标的位置；没记过就给空，调用方先去找到它再回来检查。 */
    public Optional<WorldPosition> landmarkPosition(String name) {
        return places.place(name);
    }

    /** 目标位置落不落在任何一个额外保护地标的地盘里。 */
    public Optional<String> protectedLandmarkHit(WorldPosition position, Set<String> protectedLandmarks) {
        for (String name : protectedLandmarks) {
            Optional<WorldPosition> spot = places.place(name);
            if (spot.isEmpty()) continue;
            double dx = spot.get().x() - position.x();
            double dy = spot.get().y() - position.y();
            double dz = spot.get().z() - position.z();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= LANDMARK_RADIUS) return Optional.of(name);
        }
        return Optional.empty();
    }

    private boolean inProtectedLandmark(WorldPosition position, Set<String> protectedLandmarks) {
        return protectedLandmarkHit(position, protectedLandmarks).isPresent();
    }

    /**
     * 一片范围（以 center 为心、radius 格为半径的球）碰不碰得到记住的区域或这次任务额外保护的地标的地盘：
     * 碰得到就说是哪一块，例如"记住的区域「河东的农场」"。
     *
     * <p>末影水晶这类一炸一大片的事用它：只看这片范围和记住的地盘有没有交叠，不逐格问方块归属——
     * 归属没问到服务端的格一律按受保护，拿逐格的判断查一片没人碰过的末地石，会把整片都判成碰不得。
     * 维度不同的不算交叠（一方没写维度就当相配，宁可多算进保护）。
     */
    public Optional<String> protectedAreaNear(WorldPosition center, double radius, Set<String> protectedLandmarks) {
        for (RememberedRegion region : regions.regions()) {
            if (sameDimension(region.center(), center)
                    && distance(region.center(), center) <= region.radiusBlocks() + radius) {
                return Optional.of("记住的区域「" + region.name() + "」");
            }
        }
        for (String name : protectedLandmarks) {
            Optional<WorldPosition> spot = places.place(name);
            if (spot.isPresent() && sameDimension(spot.get(), center)
                    && distance(spot.get(), center) <= LANDMARK_RADIUS + radius) {
                return Optional.of("额外保护的地标「" + name + "」");
            }
        }
        return Optional.empty();
    }

    // 两个位置在不在同一个维度：一方没写维度（角色当前所在维度）就当相配。
    private static boolean sameDimension(WorldPosition a, WorldPosition b) {
        return a.dimension() == null || b.dimension() == null || a.dimension().equals(b.dimension());
    }

    private static double distance(WorldPosition a, WorldPosition b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
