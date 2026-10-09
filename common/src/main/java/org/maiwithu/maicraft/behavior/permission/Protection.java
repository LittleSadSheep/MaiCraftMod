// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
        // 记录与区域都说不清，才靠猜；猜像就当是。
        return guesses.likelyPlayerMade(position, blockType);
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
}
