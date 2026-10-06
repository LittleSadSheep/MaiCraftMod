// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.core.task.base.LandmarkProtection;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

/** 跟随已经授权的身体移动补光；不导航、不停步、不替换主任务，也不为补火把去取料。 */
public final class AutomaticLighting {
    private static final AutomaticLighting INSTANCE = new AutomaticLighting();
    private LocalPlayer owner;
    private OffhandTorchPlacer placer = new OffhandTorchPlacer();
    // 进入游戏时不自行换副手或插灯；只有 LLM 显式要求开启后，才允许随行补光借用身体。
    private boolean enabled = false;
    private int minimum = 8;
    private List<String> protectedLabels = List.of();
    private final Set<BlockPos> visited = new LinkedHashSet<>();
    private final List<Map<String, Object>> placements = new ArrayList<>();
    private BlockPos lastAttemptOrigin;
    private String state = "disabled";
    private String observationProblem;

    public static AutomaticLighting get() { return INSTANCE; }

    public void configure(LocalPlayer player, boolean enabled, int minimum, List<String> protectedLabels) {
        // 配置只改本会话的开关、阈值和保护名称；停用保留已经插下的灯、当前副手以及可查询的路线证据。
        bind(player);
        this.enabled = enabled;
        this.minimum = minimum;
        this.protectedLabels = List.copyOf(protectedLabels);
        state = enabled ? "enabled" : "disabled";
    }

    private void bind(LocalPlayer player) {
        if (owner == player) return;
        // 换玩家实例时清掉旧身体的路线与动作对象；此处保留开关，只有连接清理调用 reset 才恢复默认关闭。
        owner = player;
        placer = new OffhandTorchPlacer();
        visited.clear(); placements.clear(); lastAttemptOrigin = null;
        state = enabled ? "waiting_for_movement" : "disabled"; observationProblem = null;
    }

    public void reset() {
        owner = null; placer = new OffhandTorchPlacer(); visited.clear(); placements.clear();
        // 新会话重新等待开启指令，不继承上次连接的补光授权。
        enabled = false; minimum = 8; protectedLabels = List.of(); lastAttemptOrigin = null;
        state = "disabled"; observationProblem = null;
    }

    public void tick(LocalPlayerContext context, boolean allowed) {
        try { advance(context, allowed); observationProblem = null; }
        catch (RuntimeException unavailable) {
            // 补光只是助手；光照、背包或模组观察暂时不可用时保留事实，不能让主任务跟着异常退出。
            // 观察失败也要撤回还没提交的补光转向，避免镜头停在半路等一支永远不会出手的火把。
            placer.releaseAim(context);
            state = "observation_unavailable"; observationProblem = unavailable.toString();
        }
    }

    private void advance(LocalPlayerContext context, boolean allowed) {
        bind(context.player());
        var receipt = placer.poll(context);
        if (receipt != null) {
            var at = placer.target().pos();
            placements.add(Map.of("position", position(at), "status", receipt.status().name(), "detail", receipt.detail()));
            state = receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED ? "light_settling" : "placement_unconfirmed";
        }
        // 关闭只停止新动作，已经发出的火把继续读回执；自救、暂停与区域补光独占时同样只观察。
        if (!enabled) { placer.releaseAim(context); state = "disabled"; return; }
        if (!context.permitsNativeActions()) { placer.releaseAim(context); return; }
        if (CompanionTickDispatcher.current() instanceof SemanticLightAreaTaskRecord) { placer.releaseAim(context); return; }
        // 指定区域拥有自己的灯位与验收；随行助手不能同时插灯污染该任务的材料和覆盖回执。
        if (CompanionTickDispatcher.current() instanceof IntentTaskRecord intent && (intent.paused()
                || intent.stepIndex() < intent.steps().size() && intent.steps().get(intent.stepIndex()).ability().equals("maicraft:light_area"))) {
            placer.releaseAim(context);
            return;
        }
        BlockPos feet = context.player().blockPosition(), eye = BlockPos.containing(context.player().getEyePosition());
        if (!context.level().isLoaded(feet) || !context.level().isLoaded(eye)) { placer.releaseAim(context); state = "light_sample_unloaded"; return; }
        visited.add(feet.immutable()); visited.add(eye.immutable());
        // 补光正在把镜头转向某一支还没提交的火把时，本刻继续喂给它同一步转向；
        // 此时准星已记在补光名下，若按"没出手就让位"处理会立刻撤回，镜头只能在灯位和路线之间来回甩。
        if (placer.aiming()) {
            if (!context.permitsNativeActions()) { placer.releaseAim(context); state = "yielding_to_primary"; return; }
            var pendingTarget = placer.target();
            // 转向期间出手同样要受"走够两格才补下一支"的限制，否则同一个位置会一直撒灯。
            if (lastAttemptOrigin != null && feet.distSqr(lastAttemptOrigin) < 4) {
                placer.releaseAim(context);
                state = "waiting_for_route_progress";
                return;
            }
            if (protection(context.player()).run(() -> placer.place(context, pendingTarget, Set.of())))
                lastAttemptOrigin = feet.immutable();
            state = placer.state();
            return;
        }
        // 主任务忙碌时经过的暗格也属于真实路线，先保留观察再让位，不能只抽取成功插灯的片段宣称覆盖。
        // 让位时撤回还没提交的补光转向，镜头立刻平滑转回主任务方向，不占用主任务的准星。
        if (!allowed || !OffhandTorchPlacer.idle(context)) { placer.releaseAim(context); state = "yielding_to_primary"; return; }
        int light = Math.min(context.level().getBrightness(LightLayer.BLOCK, feet), context.level().getBrightness(LightLayer.BLOCK, eye));
        if (light >= minimum) { placer.releaseAim(context); state = "bright_enough"; return; }
        // 距上次提交站位不足两格时不再出手；原生拒绝也保留这个限制，避免遮挡或高阈值造成原地撒灯。
        if (lastAttemptOrigin != null && feet.distSqr(lastAttemptOrigin) < 4) { placer.releaseAim(context); state = "waiting_for_route_progress"; return; }
        var protection = protection(context.player());
        if (!protection.problems().isEmpty()) { placer.releaseAim(context); state = "unresolved_protection"; return; }
        if (!placer.prepare(context)) { placer.releaseAim(context); state = placer.state(); return; }
        var target = protection.run(() -> RoutineTorchPlacement.find(context.player(), Set.of()));
        if (target == null) { placer.releaseAim(context); state = "no_reachable_support"; return; }
        if (protection.run(() -> placer.place(context, target, Set.of()))) lastAttemptOrigin = feet.immutable();
        state = placer.state();
    }

    private LandmarkProtection protection(LocalPlayer player) {
        // 配置名称与当前主任务名称一起解析；LandmarkProtection 只标记地标锚点，不在这里读取整片区域的历史足迹。
        Set<String> labels = new LinkedHashSet<>(protectedLabels);
        if (CompanionTickDispatcher.current() instanceof IntentTaskRecord intent && intent.stepIndex() < intent.steps().size()) {
            var step = intent.steps().get(intent.stepIndex()); labels.addAll(step.inheritedProtectionLabels());
            var explicit = step.parameters().getAsJsonArray("protected_labels");
            if (explicit != null) explicit.forEach(label -> labels.add(label.getAsString()));
        }
        return LandmarkProtection.resolve(List.copyOf(labels), IntentRuntime.get().landmarks(), player.level().dimension().location().toString());
    }

    public Map<String, Object> snapshot(LocalPlayer player) {
        bind(player);
        List<Map<String, Object>> dark = new ArrayList<>();
        List<List<Integer>> unknown = new ArrayList<>();
        int lowest = 15, observed = 0;
        // 查询时重新读实际方块光；已卸载路线单列未知，不把插灯数量或理论传播冒充最低亮度验收。
        for (BlockPos pos : visited) {
            if (!player.level().isLoaded(pos)) { unknown.add(position(pos)); continue; }
            int light;
            try { light = player.level().getBrightness(LightLayer.BLOCK, pos); }
            catch (RuntimeException unavailable) { unknown.add(position(pos)); continue; }
            observed++; lowest = Math.min(lowest, light);
            if (light < minimum) dark.add(Map.of("position", position(pos), "block_light", light));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled); result.put("minimum_light", minimum); result.put("state", state);
        result.put("source", "minecraft:torch"); result.put("hand", "offhand");
        // 启用只是配置完成，副手准备可能尚未执行；直接给出当前火把分布，不能让期望手别冒充实际持有。
        result.put("torch_inventory", Map.of("total_count", PlayerInv.count(player.getInventory(), Items.TORCH),
                "backpack_count", PlayerInv.carriedCount(player.getInventory(), Items.TORCH),
                "off_hand_count", player.getOffhandItem().is(Items.TORCH) ? player.getOffhandItem().getCount() : 0));
        result.put("scope", "visited_feet_and_eyes_only"); result.put("dimension", player.level().dimension().location().toString());
        result.put("observed_cells", observed); result.put("minimum_observed_block_light", observed == 0 ? null : lowest);
        result.put("below_target", dark); result.put("unloaded_cells", unknown); result.put("placements", List.copyOf(placements));
        // 这里只验收本身体采样过且此刻仍可读取的脚部、眼部格；即使全部达标，也不代表通道侧面或整座基地已补齐。
        result.put("coverage_verified", observed > 0 && dark.isEmpty() && unknown.isEmpty() && !placer.pending());
        result.put("protected_labels", protectedLabels);
        if (observationProblem != null) result.put("observation_problem", observationProblem);
        return result;
    }

    private static List<Integer> position(BlockPos pos) { return List.of(pos.getX(), pos.getY(), pos.getZ()); }
}
