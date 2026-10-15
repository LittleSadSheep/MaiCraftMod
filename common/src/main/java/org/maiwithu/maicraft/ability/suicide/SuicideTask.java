// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeedsOff;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 寻死：收起护甲与图腾 → 挑一种死法 → 走过去 → 接触危险 → 死 → 重生后按原件穿回护甲。
 *
 * <p>死了以后这个任务什么都不用做：控制循环停摆，挂死亡恢复问题，普通世界 2 秒自动点重生，重生后接着推进它；
 * 它看到重生次数比开始时多了，就知道死过了，转去穿回护甲，穿完算完成。死在哪一步都算。
 *
 * <p>从收护具到死，任务自己声明关掉生存需求：不然自卫会去打要靠的怪、防摔会垫水、贴边会退回来。
 * 重生后、穿回护甲时、要停下时生存需求恢复。没死成要结束（附近没有死法、超时）也先把护甲穿回再失败；
 * 暂停、取消时先穿回再停（像人不打算马上死了先把甲穿上），恢复后重新收。
 */
final class SuicideTask extends PhasedTask<SuicideTask.Phase> implements SurvivalNeedsOff {

    /** 寻死的进度。 */
    enum Phase { STOW, CHOOSE, APPROACH, EXPOSE, STAND_IN, HOLD, MAKE, ENDURE, RE_EQUIP }

    /** 类别 C：接触一处危险后这么多刻（20 秒）没再掉血，就放弃这一处换下一处。 */
    static final int NO_HURT_TICKS = 400;
    /** 类别 F：重生后背包内容晚几刻才同步过来，最多等这么多刻（10 秒）找原件。 */
    static final int INVENTORY_SYNC_TICKS = 200;
    /** 类别 F：迈下悬崖这么多刻后落了地、比站位低 2 格以上还活着，就是摔不死。 */
    static final int FALL_LANDED_TICKS = 10;
    /** 类别 C：一处走不到时，同一种死法站位离它这么近（格）的一起放弃：挨着的多半也走不到。 */
    static final double SAME_AREA = 4;
    /** 寻死用完了时限也要留出穿回护甲的工夫：进度跟踪的上限多给这么多刻。 */
    static final long RE_EQUIP_ALLOWANCE = 20L * 60;

    private final SuicideInput input;
    private final SuicidePlayer player;
    private final SuicideWorld world;
    private final SuicideMoves moves;
    private final GearChanges gear;
    private final StowedGear stowed = new StowedGear();
    private final Set<String> abandoned = new HashSet<>();
    private final Set<WayToDie> givenUp = EnumSet.noneOf(WayToDie.class);
    /** 放弃过的地方按死法、原因计数：没死成时摊开讲一次，同死法同原因合并。 */
    private final Map<WayToDie, Map<String, Integer>> tally = new EnumMap<>(WayToDie.class);
    /** 造过危险的格子：同一格再点、再倒不再记一条放方块。 */
    private final Set<BlockPos> madeAt = new HashSet<>();
    /** 同一格又造危险用掉的东西（火焰弹、岩浆桶）：离开这一格时合成一条记下。 */
    private final Map<String, Integer> reused = new LinkedHashMap<>();
    /** 穿回了的栏位：结论里说穿回了几件。 */
    private final Set<GearSlotName> restoredSlots = EnumSet.noneOf(GearSlotName.class);
    private DeathSpotFinder finder;
    private DeathSpot spot;
    private WayToDie lastWay;
    private long respawnsAtStart = -1;
    private boolean died;
    private long attemptTicks;
    private int stowIndex;
    private GearSlotName stowing;
    private SuicidePlayer.WornPiece stowingPiece;
    private StowedGear.Piece restoring;
    private long reEquipWaited;
    private boolean reEquipDone;
    private Problem endProblem;
    private boolean windDown;
    private long exposedTicks;
    private long lastHurtAt;
    private float lastHealth;
    /** 这一回要拿到手上的点火物或岩浆桶；身上没有了为 null。 */
    private String holding;

    SuicideTask(SuicideInput input, SuicidePlayer player, SuicideWorld world, SuicideMoves moves, GearChanges gear) {
        super("寻死", Phase.STOW, new ProgressTracker(input.maxSeconds() * 20 + RE_EQUIP_ALLOWANCE,
                input.maxSeconds() * 20 + RE_EQUIP_ALLOWANCE));
        this.input = input;
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.gear = Objects.requireNonNull(gear, "gear");
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            case STOW -> enterStow();
            case APPROACH -> moves.walkTo(spot.stand(), input.permissions());
            case EXPOSE -> spot.way() == WayToDie.HOSTILE_MOB
                    ? moves.keepNear(spot.mobId(), spot.mobType(), input.permissions()) : moves.stepInto(spot);
            case STAND_IN -> moves.standIn(spot.entry());
            case HOLD -> enterHold();
            case MAKE -> moves.makeHazard(spot.way(), spot.entry());
            case RE_EQUIP -> enterReEquip();
            case CHOOSE, ENDURE -> null;
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        // 死过了：重生次数比开始时多了。死亡停摆时任务不推进，重生后第一刻就看到；死在哪一步都算。
        if (respawnsAtStart < 0) respawnsAtStart = player.respawns();
        if (!died && player.respawns() > respawnsAtStart) {
            died = true;
            leaveStow("死了");
            recordProgress("死了，已经重生");
            return Next.go(Phase.RE_EQUIP, "死过了，重生了");
        }
        if (phase == Phase.RE_EQUIP) return reEquip(context);
        // 要停下（暂停、取消）且有脱下的护甲：先穿回再停。
        if (windDown) {
            leaveStow("要停下了");
            return Next.go(Phase.RE_EQUIP, "要停下了，先把护甲穿回");
        }
        // 时限按寻死实际被推进的刻数算：死亡停摆、暂停、穿回护甲都不算。
        if (++attemptTicks > input.maxSeconds() * 20) {
            leaveStow("到时限了");
            return endWith(Problem.of(Problem.Kind.STUCK, input.maxSeconds() + " 秒内没死成",
                    "换个有岩浆或高崖的地方再试，或带上打火石"));
        }
        return switch (phase) {
            case STOW -> stow(context);
            case CHOOSE -> choose();
            case APPROACH -> approach(context);
            case EXPOSE -> expose(context);
            case STAND_IN -> standIn(context);
            case HOLD -> hold(context);
            case MAKE -> make(context);
            case ENDURE -> endure();
            case RE_EQUIP -> reEquip(context);
        };
    }

    // 按头、胸、腿、脚、副手、主手的顺序找下一件要收的：护甲有就脱，手上只收不死图腾。
    private Action enterStow() {
        stowing = null;
        while (stowIndex < StowedGear.ORDER.size()) {
            GearSlotName slot = StowedGear.ORDER.get(stowIndex++);
            boolean needs = StowedGear.armor(slot) ? player.worn(slot).isPresent() : player.holdsTotem(slot);
            if (!needs) continue;
            Optional<Action> takeOff = gear.takeOff(slot);
            SuicidePlayer.WornPiece piece = player.worn(slot).orElse(null);
            if (takeOff.isEmpty()) {
                stowed.stillOn(slot, itemOf(piece), "取下装备的界面操作还没接上");
                continue;
            }
            stowing = slot;
            stowingPiece = piece;
            return takeOff.get();
        }
        return null;
    }

    private Next<Phase> stow(TickContext context) {
        if (stowing == null) return Next.go(Phase.CHOOSE, "护甲与图腾收好了");
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                settleStow(null);
                yield Next.go(Phase.STOW, "下一件");
            }
            case ActionStatus.Failed failed -> {
                settleStow(failed.problem().headline());
                yield Next.go(Phase.STOW, "下一件");
            }
        };
    }

    // 以取下之后栏位里的实际东西为准：护甲栏空了、手上不再是图腾才算收走；收不走记下，照样往下做。
    private void settleStow(String failure) {
        GearSlotName slot = stowing;
        stowing = null;
        boolean armor = StowedGear.armor(slot);
        boolean off = armor ? player.worn(slot).isEmpty() : !player.holdsTotem(slot);
        String item = itemOf(stowingPiece);
        if (off && armor) {
            stowed.stowed(new StowedGear.Piece(slot, stowingPiece));
            restoredSlots.remove(slot);
            recordChange(new Change(Change.Kind.ITEM_GAINED, item, 1, "从 " + slot.paramName() + " 取下放进了背包，死后穿回"));
        } else if (off) {
            stowed.totemStowed(slot);
            recordChange(new Change(Change.Kind.OTHER, item, 1, "收起了 " + slot.paramName() + " 上的不死图腾"));
        } else {
            stowed.stillOn(slot, item, failure == null ? "没能确认取下（背包放不下或有绑定诅咒）" : failure);
        }
    }

    // 收到一半要离开（死了、要停下、到时限）：手上这一件按此刻栏位里的样子记账，不让它从账上漏掉。
    private void leaveStow(String why) {
        if (phase() == Phase.STOW && stowing != null) settleStow(why);
    }

    private static String itemOf(SuicidePlayer.WornPiece piece) {
        return piece == null ? "minecraft:totem_of_undying" : piece.itemId();
    }

    private Next<Phase> choose() {
        if (finder == null) {
            finder = new DeathSpotFinder(world, player, player.feet(), input.radius(), input.allowedWays());
        }
        if (!finder.scanned()) {
            finder.scan();
            return Next.stay();
        }
        return switch (finder.choose(abandoned, givenUp)) {
            case DeathSpotFinder.Choice.Found found -> pick(found.spot());
            case DeathSpotFinder.Choice.Searching searching -> Next.stay();
            case DeathSpotFinder.Choice.Nothing nothing -> endWith(nothingLeft());
        };
    }

    // 挑中一处：从这一刻起算这一处有没有掉血；自造的直接站进格子，怪直接跟过去，地形的先走到站位。
    private Next<Phase> pick(DeathSpot chosen) {
        spot = chosen;
        lastWay = chosen.way();
        exposedTicks = 0;
        lastHurtAt = 0;
        lastHealth = player.health();
        recordProgress("挑中" + chosen.describe());
        Phase next = chosen.way().carried() ? Phase.STAND_IN
                : chosen.way() == WayToDie.HOSTILE_MOB ? Phase.EXPOSE : Phase.APPROACH;
        return Next.go(next, "挑中" + chosen.describe());
    }

    private Next<Phase> approach(TickContext context) {
        if (!finder.stillThere(spot)) return abandon("走到之前那里变了");
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.EXPOSE, "走到" + spot.way().label + "旁边了");
            case ActionStatus.Failed failed -> abandonArea("走不到");
        };
    }

    private Next<Phase> expose(TickContext context) {
        if (!finder.stillThere(spot)) {
            return abandon(spot.way() == WayToDie.HOSTILE_MOB ? "怪死了或不见了" : "那里变了（岩浆凝固、仙人掌没了）");
        }
        Next<Phase> window = hurtWindow();
        if (window != null) return window;
        // 迈下去落了地、比站位低了 2 格以上还活着：这处摔不死，换下一处。
        if (spot.way() == WayToDie.FALL && exposedTicks > FALL_LANDED_TICKS && player.onGround()
                && player.feet().getY() < spot.stand().getY() - 2) {
            return abandon("摔下去没死");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.stay();
            case ActionStatus.Failed failed -> abandon(failed.problem().kind() == Problem.Kind.TARGET_GONE
                    ? "怪死了或不见了" : spot.way() == WayToDie.HOSTILE_MOB ? "走不到怪跟前" : "迈不进去");
        };
    }

    // 接触以后掉没掉血：掉了就续上，这么久都没掉就放弃这一处（掉血不问是谁打的）。
    private Next<Phase> hurtWindow() {
        exposedTicks++;
        float health = player.health();
        if (health < lastHealth) {
            lastHurtAt = exposedTicks;
            recordProgress("掉血了");
        }
        lastHealth = health;
        return exposedTicks - lastHurtAt >= NO_HURT_TICKS ? abandon("20 秒没再掉血") : null;
    }

    private Next<Phase> standIn(TickContext context) {
        if (!finder.stillThere(spot)) return abandon("格子变了，不能点火、倒岩浆了");
        Next<Phase> window = hurtWindow();
        if (window != null) return window;
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(hazardPresent() ? Phase.ENDURE : Phase.HOLD, "站进去了");
            case ActionStatus.Failed failed -> abandon("站不进去");
        };
    }

    // 点火拿打火石（有耐久能反复点）先于火焰弹；倒岩浆拿岩浆桶。已经在手上就不用换。
    private Action enterHold() {
        holding = itemFor(spot.way());
        return holding == null ? null : moves.hold(holding).orElse(null);
    }

    private Next<Phase> hold(TickContext context) {
        Next<Phase> window = hurtWindow();
        if (window != null) return window;
        if (holding == null) {
            return player.onFire() ? Next.go(Phase.ENDURE, "身上还着火") : abandon("身上的点火物或岩浆桶用完了");
        }
        if (action() == null) return Next.go(Phase.MAKE, "已经拿在手上");
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.MAKE, "拿到手上了");
            case ActionStatus.Failed failed -> giveUp("拿不到手上");
        };
    }

    // 右键出去：确认了就站在里面受着；没出效果（游戏没让、没等到确认、瞄不准）这种死法不再用，不换格反复试。
    private Next<Phase> make(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                madeHazard();
                yield Next.go(Phase.ENDURE, spot.way() == WayToDie.FIRE ? "点着了" : "倒出来了");
            }
            case ActionStatus.Failed failed -> {
                Problem problem = failed.problem();
                if (problem.kind() == Problem.Kind.STUCK) {
                    recordUnconfirmed(new Change(Change.Kind.OTHER, holding, 1,
                            spot.way().label + "右键出去了，没等到游戏确认：" + problem.headline()));
                }
                yield giveUp(switch (problem.kind()) {
                    case REFUSED_BY_GAME -> spot.way() == WayToDie.FIRE ? "游戏没让点着" : "游戏没让倒出来";
                    case STUCK -> "右键出去了，没等到确认";
                    default -> "瞄不准脚下";
                });
            }
        };
    }

    // 同一格第一次造成记放方块与用掉的东西；再点、再倒只数用掉了几个，离开这一格时合成一条。
    private void madeHazard() {
        WayToDie way = spot.way();
        BlockPos cell = spot.entry();
        boolean consumes = way == WayToDie.LAVA_BUCKET || "minecraft:fire_charge".equals(holding);
        if (madeAt.add(cell)) {
            recordChange(new Change(Change.Kind.BLOCK_PLACED, way == WayToDie.FIRE ? "minecraft:fire" : "minecraft:lava", 1,
                    (way == WayToDie.FIRE ? "在脚下点了火（" : "在脚下倒了岩浆（") + cell.toShortString() + "）"));
            if (consumes) {
                recordChange(new Change(Change.Kind.ITEM_CONSUMED, holding, 1,
                        way == WayToDie.FIRE ? "点火用掉" : "倒空成了空桶"));
            }
        } else if (consumes) {
            reused.merge(holding, 1, Integer::sum);
        }
        recordProgress(way.label + "成了");
    }

    private Next<Phase> endure() {
        Next<Phase> window = hurtWindow();
        if (window != null) return window;
        if (!player.feet().equals(spot.entry())) return Next.go(Phase.STAND_IN, "被挤出去了，走回去");
        if (hazardPresent()) return Next.stay();
        // 火自己灭了、岩浆被冲走了：身上还有就原地再造；没有了、身上也不着火就放弃这一格。
        if (itemFor(spot.way()) != null) {
            return Next.go(Phase.HOLD, spot.way() == WayToDie.FIRE ? "火灭了，再点" : "岩浆没了，再倒");
        }
        if (player.onFire()) return Next.stay();
        return abandon("火灭了、岩浆没了，身上也没有能再用的");
    }

    private boolean hazardPresent() {
        return spot.way() == WayToDie.FIRE ? world.fireAt(spot.entry()) : world.lava(spot.entry());
    }

    private String itemFor(WayToDie way) {
        if (way == WayToDie.LAVA_BUCKET) {
            return player.carries(WayToDie.LAVA_BUCKET_ITEM) ? WayToDie.LAVA_BUCKET_ITEM : null;
        }
        return WayToDie.IGNITERS.stream().filter(player::carries).findFirst().orElse(null);
    }

    private Next<Phase> giveUp(String reason) {
        givenUp.add(spot.way());
        return abandon(reason);
    }

    // 一处走不到：同一种死法挨着的站位一起放弃，不一处处试过去把时限耗光。
    private Next<Phase> abandonArea(String reason) {
        abandoned.addAll(finder.sameWayNear(spot, SAME_AREA));
        return abandon(reason);
    }

    // 放弃这一处：记下原因（同死法同原因合并计数），回去挑下一处；已经发生的烧伤、摔伤不回滚。
    private Next<Phase> abandon(String reason) {
        flushReused();
        tally.computeIfAbsent(spot.way(), way -> new LinkedHashMap<>()).merge(reason, 1, Integer::sum);
        recordAttempt(spot.way().label, reason);
        abandoned.add(spot.key());
        spot = null;
        return Next.go(Phase.CHOOSE, reason);
    }

    private void flushReused() {
        reused.forEach((item, count) -> recordChange(new Change(Change.Kind.ITEM_CONSUMED, item, count,
                "同一格又点火、倒岩浆用掉")));
        reused.clear();
    }

    // 没死成要结束：先穿回护甲，穿完再按这个问题失败。
    private Next<Phase> endWith(Problem problem) {
        endProblem = problem;
        return Next.go(Phase.RE_EQUIP, "没死成，先把护甲穿回");
    }

    // 挑不出任何一处：按死法摊开讲一次——试过的说放弃的原因与几处，没试的说为什么用不上。
    private Problem nothingLeft() {
        Map<WayToDie, String> unavailable = finder.whyUnavailable(givenUp);
        List<String> parts = new ArrayList<>();
        for (WayToDie way : WayToDie.values()) {
            Map<String, Integer> tried = tally.get(way);
            if (tried != null) {
                List<String> reasons = new ArrayList<>();
                tried.forEach((reason, count) -> reasons.add(reason + " " + count + " 处"));
                parts.add(way.label + "：" + String.join("、", reasons));
            } else if (unavailable.containsKey(way)) {
                parts.add(way.label + "：" + unavailable.get(way));
            }
        }
        String message = "附近 " + input.radius() + " 格内没有能用的死法";
        // 身上带着点火物或岩浆桶、只是许可不让放方块：缺的是许可，说清提哪一项就能接着做。
        if (input.via() == null && !input.placesBlocks() && carriesForCarriedWays()) {
            parts.add("随身点火、倒岩浆：这次任务不许动方块（change_blocks=none）");
            return Problem.of(Problem.Kind.NEED_APPROVAL, message + "：" + String.join("；", parts),
                    "把 change_blocks 提到 temporary 或更高，就能原地点火或倒岩浆");
        }
        return Problem.of(Problem.Kind.NOT_FOUND, message + (parts.isEmpty() ? "" : "：" + String.join("；", parts)),
                "换到有岩浆或高崖的地方再试，或带上打火石、岩浆桶");
    }

    private boolean carriesForCarriedWays() {
        return itemFor(WayToDie.FIRE) != null || itemFor(WayToDie.LAVA_BUCKET) != null;
    }

    // 挑下一件能穿的：原部位被别的占了就不换；原件还没在背包里（重生后背包晚到）先跳过，等一等再来。
    private Action enterReEquip() {
        restoring = null;
        for (StowedGear.Piece piece : stowed.toRestore()) {
            if (player.worn(piece.slot()).isPresent()) {
                stowed.notRestored(piece, "原部位已经有别的装备，不换");
                continue;
            }
            int from = player.carriedSlotOf(piece.worn());
            if (from < 0) continue;
            Optional<Action> wear = gear.wearFrom(from, piece.slot());
            if (wear.isEmpty()) {
                stowed.notRestored(piece, "穿装备的界面操作还没接上");
                continue;
            }
            restoring = piece;
            return wear.get();
        }
        return null;
    }

    private Next<Phase> reEquip(TickContext context) {
        // 穿完护甲停下过（暂停）、又被叫接着做：重新收护具，接着寻死。
        if (reEquipDone && windDown && !died && endProblem == null) {
            windDown = false;
            reEquipDone = false;
            stowIndex = 0;
            return Next.go(Phase.STOW, "接着寻死，重新收护甲");
        }
        if (restoring != null) {
            ActionStatus status = runAction(context);
            if (status instanceof ActionStatus.Running) return Next.stay();
            settleRestore(status);
            return Next.go(Phase.RE_EQUIP, "下一件");
        }
        if (stowed.hasPiecesToRestore()) {
            boolean ready = stowed.toRestore().stream().anyMatch(piece ->
                    player.worn(piece.slot()).isPresent() || player.carriedSlotOf(piece.worn()) >= 0);
            if (ready) return Next.go(Phase.RE_EQUIP, "原件到背包里了");
            if (++reEquipWaited < INVENTORY_SYNC_TICKS) return Next.stay();
            for (StowedGear.Piece piece : stowed.toRestore()) stowed.notRestored(piece, "背包里找不到原件");
        }
        return finishRestore();
    }

    // 以穿之后栏位里的实际东西为准：原部位是那一件了才算穿回。
    private void settleRestore(ActionStatus status) {
        StowedGear.Piece piece = restoring;
        restoring = null;
        boolean back = player.worn(piece.slot()).map(now -> now.itemId().equals(piece.worn().itemId())).orElse(false);
        if (back) {
            stowed.restored(piece);
            restoredSlots.add(piece.slot());
            recordChange(new Change(Change.Kind.OTHER, piece.worn().itemId(), 1, "穿回了 " + piece.slot().paramName()));
        } else {
            stowed.notRestored(piece, status instanceof ActionStatus.Failed failed
                    ? failed.problem().headline() : "没能确认穿上");
        }
    }

    // 护甲结清了：死过就完成；没死成按问题失败；要停下就站着等目标暂停或取消。
    private Next<Phase> finishRestore() {
        reEquipDone = true;
        if (died) return Next.done(TaskResult.done(doneSummary()));
        if (endProblem != null) return Next.fail(endProblem);
        if (windDown) return Next.stay();
        return Next.go(Phase.CHOOSE, "护甲穿好了，接着找死法");
    }

    private String doneSummary() {
        BlockPos at = player.feet();
        String text = "死了，在 " + at.getX() + ", " + at.getY() + ", " + at.getZ() + " 重生";
        int total = restoredSlots.size() + stowed.notRestored().size();
        if (total == 0) return text;
        return text + "；脱下的 " + total + " 件护甲" + (restoredSlots.size() == total ? "都穿回了"
                : "穿回了 " + restoredSlots.size() + " 件");
    }

    @Override protected void onPhaseChange(Phase from, Phase to, String why) {
        // 进穿回阶段（死过、没死成、要停下）：离开那一格的用量合成一条，背包同步从头等。
        if (to == Phase.RE_EQUIP && from != Phase.RE_EQUIP) {
            flushReused();
            reEquipWaited = 0;
            reEquipDone = false;
        }
    }

    /** 暂停、取消时：身上还有脱下没穿回的护甲，先穿回再停。 */
    @Override public boolean stopSafely() {
        boolean landing = super.stopSafely();
        windDown = stowed.hasPiecesToRestore();
        return landing || windDown;
    }

    @Override public boolean stoppedSafely() {
        return super.stoppedSafely() && (!windDown || reEquipDone);
    }

    /** 从收护具到死关掉生存需求；重生后、穿回护甲时、要停下时恢复。 */
    @Override public boolean survivalNeedsOff() {
        return !died && !windDown && phase() != Phase.RE_EQUIP;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case STOW -> "收起护甲和图腾";
            case CHOOSE -> "找死法";
            case APPROACH -> "走过去";
            case EXPOSE -> "接触危险";
            case STAND_IN -> "站进格子";
            case HOLD -> "拿出点火物或岩浆桶";
            case MAKE -> "点火或倒岩浆";
            case ENDURE -> "站在火里或岩浆里";
            case RE_EQUIP -> "穿回护甲";
        };
    }

    @Override protected List<String> remaining() {
        return died ? List.of() : List.of("死一次回出生点或床边");
    }

    @Override protected ResultDetails details() {
        List<String> notRestored = new ArrayList<>(stowed.notRestored());
        for (StowedGear.Piece piece : stowed.toRestore()) notRestored.add(piece.label() + "：还在背包里，没来得及穿回");
        return new SuicideDetails(lastWay == null ? null : lastWay.param, input.keepInventory(),
                stowed.stillWorn(), stowed.totemsStillHeld(), notRestored);
    }

    /**
     * 寻死特有的结果细节。
     *
     * @param way              最后用的死法；一处都没挑到为 null
     * @param keepInventory    怎么知道开着死亡不掉落的：game_rule / caller_confirmed
     * @param armorStillWorn   没脱下来、带着死的护甲与原因
     * @param totemsStillHeld  没收起来的不死图腾与原因
     * @param armorNotRestored 脱下了、没穿回的护甲与原因
     */
    record SuicideDetails(String way, String keepInventory, List<String> armorStillWorn,
            List<String> totemsStillHeld, List<String> armorNotRestored) implements ResultDetails {
        SuicideDetails {
            armorStillWorn = List.copyOf(armorStillWorn);
            totemsStillHeld = List.copyOf(totemsStillHeld);
            armorNotRestored = List.copyOf(armorNotRestored);
        }
    }
}
