// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 意外往下掉时用喷气背包刹住：穿着、背罐有气就立刻打开飞行与悬停，确认慢下来后照快速下降的做法落到地面
 * （慢慢飘会摔伤，所以关掉落、快到时再刹），站稳后把两个开关拨回掉下去之前的样子。不用找水、不留水。
 */
final class JetpackFallBrake implements CatchesFall {

    /** 往下最多找这么多格认落点。 */
    private static final int LOOK_DOWN = 128;

    private final JetpackSwitches switches;
    private final String itemId;
    private final boolean wasActive;
    private final boolean wasHovering;
    private final BlockPos landing;
    private final float healthAtStart;
    private FastDrop drop;
    private boolean slowed;
    private boolean landed;
    private boolean restored;
    private boolean failed;
    private String detail = "打开喷气背包刹车";

    private JetpackFallBrake(CreateJetpackCompat compat, JetpackControls.Pack pack, BlockPos landing, float health) {
        this.switches = new JetpackSwitches(Objects.requireNonNull(compat, "compat"));
        this.itemId = pack.itemId();
        this.wasActive = pack.active();
        this.wasHovering = pack.hovering();
        this.landing = landing;
        this.healthAtStart = health;
        switches.want(true, true);
    }

    /** 刹得住这一掉吗：喷气背包穿在身上、背罐的气够开，才给一份刹车。 */
    static Optional<CatchesFall> start(CreateJetpackCompat compat, PlayerContext context) {
        LocalPlayer player = context == null ? null : context.localPlayer();
        ClientLevel level = context == null ? null : context.level();
        if (player == null || level == null) return Optional.empty();
        Optional<JetpackControls.Pack> pack = compat.pack(player);
        if (pack.isEmpty() || !pack.get().worn() || !pack.get().usable()) return Optional.empty();
        return Optional.of(new JetpackFallBrake(compat, pack.get(), groundBelow(level, player.blockPosition()),
                player.getHealth()));
    }

    @Override public boolean prepare(PlayerContext context) {
        return true;
    }

    // 每刻：先开飞行与悬停刹住；慢下来后快速下降到地面；落地站稳把开关拨回原样。
    @Override public void tick(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        ClientLevel level = context.level();
        if (player == null || level == null || restored || failed) return;
        long now = level.getGameTime();
        if (!landed && (player.onGround() || player.isInWater())) {
            landed = true;
            switches.want(wasActive, wasHovering);
            if (player.getHealth() < healthAtStart - 0.5f) {
                failed = true;
                detail = "开了喷气背包，落地还是掉了 " + String.format("%.1f", healthAtStart - player.getHealth()) + " 点血";
                return;
            }
        }
        if (landed) {
            JetpackSwitches.State state = switches.tick(player, now);
            if (state == JetpackSwitches.State.DONE) {
                restored = true;
                detail = "用喷气背包刹住落到 " + player.blockPosition().toShortString() + "，开关拨回了掉下去之前的样子";
            } else if (state == JetpackSwitches.State.UNCONFIRMED) {
                restored = true;
                detail = "用喷气背包刹住落地了；把开关拨回原样时说不清：" + switches.unclear();
            }
            return;
        }
        if (!slowed) {
            JetpackSwitches.State state = switches.tick(player, now);
            if (state == JetpackSwitches.State.UNCONFIRMED) {
                failed = true;
                detail = "喷气背包没刹住：" + switches.unclear();
                return;
            }
            if (state == JetpackSwitches.State.DONE && player.getDeltaMovement().y > FastDrop.SLOWED) {
                slowed = true;
                drop = new FastDrop(switches, landing.getY(), soft(level, player));
                detail = "喷气背包刹住了，往下落到 " + landing.toShortString();
            }
            return;
        }
        drop.tick(context, now);
        if (drop.unclear() != null) {
            failed = true;
            detail = "喷气背包往下落时开关拨不动：" + drop.unclear();
        }
    }

    // 下面是水、或离地不高：直接关掉落下去就行。
    private boolean soft(ClientLevel level, LocalPlayer player) {
        return BlockPassability.isWater(level, landing) || player.getY() - landing.getY() <= FastDrop.SAFE_DROP;
    }

    @Override public boolean complete() {
        return restored && !failed;
    }

    @Override public boolean failed() {
        return failed;
    }

    @Override public String detail() {
        return detail;
    }

    @Override public String itemId() {
        return itemId;
    }

    @Override public BlockPos landingFeet() {
        return landing;
    }

    @Override public boolean touchedDown(PlayerContext context) {
        return landed;
    }

    @Override public PlayerInput.Movement steering(PlayerContext context, float yaw) {
        return PlayerInput.Movement.STOPPED;
    }

    // 被打断：能拨回的开关拨一下，拨不回的留给结果说清。
    @Override public void stop(PlayerContext context, String reason) {
        if (context == null || context.localPlayer() == null || context.level() == null) return;
        switches.want(wasActive, wasHovering);
        switches.tick(context.localPlayer(), context.level().getGameTime());
    }

    @Override public List<Change> drainChanges() {
        return List.of();
    }

    // 正下方第一块接得住人的方块上面那一格；一直往下都是空的，就当落在世界底。
    static BlockPos groundBelow(ClientLevel level, BlockPos feet) {
        for (int down = 0; down < LOOK_DOWN; down++) {
            BlockPos cell = feet.below(down);
            if (BlockPassability.canWalkOn(level, cell.below()) || BlockPassability.isWater(level, cell)) return cell;
            if (cell.getY() <= level.getMinBuildHeight()) return cell;
        }
        return feet.below(LOOK_DOWN);
    }
}
