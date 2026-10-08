// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 内嵌 Baritone 的接缝：Baritone 的搜索与移动对象都由它自己创建，拿不到外部传入的服务，
 * 所以这里登记唯一实例，供它的分叉代码把视角请求、快捷栏选择这类回调交回来。
 * 走到的实现方每刻先用本刻的角色上下文接上，再推进 Baritone；没有接上时回调一律不生效。
 */
public final class BaritoneInternals {

    private static final AtomicReference<BaritoneInternals> ATTACHED = new AtomicReference<>();

    /** 登记唯一实例；走到结束或换角色时解除。 */
    public static void attach(BaritoneInternals internals) {
        ATTACHED.set(internals);
    }

    public static void detach(BaritoneInternals internals) {
        ATTACHED.compareAndSet(internals, null);
    }

    private static BaritoneInternals current() {
        return ATTACHED.get();
    }

    private PlayerContext tickContext;

    /** 走到的实现方每刻推进前调用：本刻的视角与快捷栏回调都写进这份上下文的输入入口。 */
    public void beginTick(PlayerContext context) {
        this.tickContext = context;
    }

    /** 本刻推进结束；之后的回调不再对应任何有效的每刻输入。 */
    public void endTick() {
        this.tickContext = null;
    }

    /**
     * Baritone 为路线瞄准请求转头。普通走路走导航视角通道（背景镜头，不抢交互准星）；
     * 挖掘与放置的精确方块瞄准走立即瞄准通道。没有本刻上下文时不转。
     */
    public static void requestLook(float yaw, float pitch, boolean precisionAim) {
        BaritoneInternals internals = current();
        PlayerContext context = internals == null ? null : internals.tickContext;
        if (context == null) return;
        if (precisionAim) {
            context.input().requestImmediateLook(yaw, pitch, context.clientTick());
        } else {
            context.input().requestNavigationLook(yaw, pitch, context.clientTick());
        }
    }

    /**
     * 把需要的工具或垫块换到手上：直接选中快捷栏格子是原版玩家的按键动作，
     * 由本地玩家在下一次 tick 自行同步到服务端。这里还没有逐刻的原生交换确认，
     * 只能选快捷栏里已有的东西；选不了返回 false，调用方按缺料处理。
     */
    public static boolean ensureHotbarSelected(LocalPlayer player, int slot) {
        if (player == null || slot < 0 || slot > 8) return false;
        player.getInventory().selected = slot;
        return true;
    }

    /**
     * 当前走到任务对这扇门登记的目标开关状态；没有登记时返回 null，按门板朝向判断。
     * 逐刻的门卡记忆还没有接到这个接缝上，先如实地交给寻路自己判断。
     */
    public static Boolean passageOpenOverride(BlockPos pos, BlockState state) {
        return null;
    }

    /** 请求停止正在进行的挖掘；逐刻的原生交互提交接入前无事可做。 */
    public static void requestStopBreaking() {}

    /**
     * 角色背包里选得出的垫块；垫块选料还没有接入，返回 null 表示按
     * Baritone 自己的易拆物品清单找，不额外登记垫块策略。
     */
    public static Object scaffoldChoice(LocalPlayer player) {
        return null;
    }

    /** 是否登记了垫块选料策略；没有接入时固定为 false。 */
    public static boolean hasScaffoldMaterialPolicy() {
        return false;
    }
}
