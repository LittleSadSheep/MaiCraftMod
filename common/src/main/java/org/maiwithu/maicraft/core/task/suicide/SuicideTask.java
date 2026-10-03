// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.suicide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.GuiPreparation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 确认死亡不掉落 -> 找危险 -> 原生走近并受伤 -> 观察死亡；只控制按键，死亡和重生由原生生命周期结算。 */
public final class SuicideTask implements Task {
    private final LocalPlayer player;
    private final SuicideRequest request;
    private final GuiPreparation gui = new GuiPreparation();
    private final Set<String> attempted = new HashSet<>();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private SuicideHazards survey;
    private SuicideHazards.Candidate candidate;
    private PlayerNav nav;
    private CompletableFuture<Boolean> ruleRead;
    private Boolean keepInventory;
    private int ticks, lastRuleRead, lastProgress, enteredTicks;
    private float previousHealth, healthLost;
    private boolean acting, armed, ended, deathObserved;
    private String detail = "Seeking a native death opportunity.";
    private String ruleSource = "unconfirmed";

    public SuicideTask(LocalPlayer player, SuicideTaskRecord record) {
        this.player = player; this.request = record.request;
    }

    @Override public TaskState tick(LocalPlayer body) {
        if (ended) return deathObserved ? TaskState.SUCCESS : TaskState.FAILED;
        if (body != player || player.isRemoved()) return finish("The original body is unavailable.", TaskState.CANCELLED);
        if (observeDeath(body)) return TaskState.SUCCESS;
        if (++ticks > request.timeoutSeconds() * 20) return finish("Timed out without observing death.", TaskState.TIMEOUT);
        // 模式与真实规则不符合用途时停止；多人客户端未同步的 GameRules 不能当作服务器证据。
        if (player.isCreative() || player.isSpectator() || player.level().getLevelData().isHardcore())
            return finish("Suicide requires a non-hardcore survival or adventure body.", TaskState.FAILED);
        if (!checkRule()) return ended ? TaskState.FAILED : TaskState.RUNNING;
        if (!gui.ready(ClientRuntime.requireContext(player), true)) return TaskState.RUNNING;
        float health = player.getHealth();
        if (previousHealth > health) { healthLost += previousHealth - health; lastProgress = ticks; }
        previousHealth = health;
        if (survey == null) survey = new SuicideHazards(player, request);
        if (candidate == null) {
            if (!survey.scan()) return TaskState.RUNNING;
            candidate = survey.choose(player, attempted);
            if (candidate == null) return finish("No remaining reachable native hazard was found in the loaded search area.", TaskState.FAILED);
            attempts.add(Map.of("method", candidate.method(), "attempt", attempts.size() + 1));
            lastProgress = ticks; enteredTicks = 0; acting = false;
        }
        Vec3 destination = candidate.destination(player);
        if (destination == null || NavigationSafetyContext.forbidsBody(BlockPos.containing(destination))) return abandon("The hazard disappeared or left the permitted area.");
        // 防火、护甲和怪物不攻击等真实结果可能让寻死不奏效；保留受伤事实并在无进展后尝试其他位置。
        if (ticks - lastProgress >= 400) return abandon("No further health loss within the attempt window.");
        if (!acting) {
            if (!SuicideHazards.valid(player, candidate)) return abandon("The observed hazard changed before approach.");
            boolean hostile = candidate.method().equals("hostile");
            Vec3 approach = hostile ? destination : Vec3.atBottomCenterOf(candidate.approach());
            boolean reached = player.distanceToSqr(approach) <= (hostile ? 25 : 0.36) && player.onGround();
            if (!reached) {
                if (nav == null) nav = PlayerNav.to(player, () -> hostile
                                ? GoalCompiler.near(BlockPos.containing(candidate.destination(player)), 4)
                                : GoalCompiler.standOn(candidate.approach()), 0.8,
                        () -> player.distanceToSqr(hostile ? candidate.destination(player) : approach) <= (hostile ? 25 : 0.36))
                        .walkingOnly();
                armed = true;
                if (nav.tick() == PlayerNav.Status.FAILED) return abandon("Native approach navigation failed: " + nav.failReason());
                return TaskState.RUNNING;
            }
            // 先完整交回普通导航，再按前进踏入危险，避免导航防摔或绕开岩浆的习惯抵消本次意图。
            if (nav != null && !nav.isSafeToCancel()) { nav.tick(); return TaskState.RUNNING; }
            stopNavigation(); acting = true; lastProgress = ticks;
        }
        armed = true; enteredTicks++;
        if (candidate.method().equals("fall") && enteredTicks > 10 && player.onGround()
                && player.getY() < candidate.approach().getY() - 2) return abandon("The native fall ended with the body still alive.");
        // 站入岩浆后停留受伤；靠怪只接近不攻击、不举盾；坠落只踏出边缘，不放水或展开鞘翅。
        if (player.isInLava() || candidate.method().equals("hostile") && player.distanceToSqr(destination) < 2.25
                || candidate.method().equals("fall") && player.getY() < candidate.approach().getY() - 0.5) {
            InputDriver.halt(player);
        } else {
            InputDriver.stepToward(player, destination, false);
            if (candidate.method().equals("hostile") && player.horizontalCollision && player.onGround()) InputDriver.jump(player);
        }
        return TaskState.RUNNING;
    }

    private boolean checkRule() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            ruleSource = "caller_confirmation"; keepInventory = request.keepInventoryConfirmed();
        } else {
            // 单人世界在服务端线程读取实际规则，并定期刷新；外部确认不能覆盖已知关闭的 keepInventory。
            ruleSource = "integrated_server";
            if (ruleRead == null && (keepInventory == null || ticks - lastRuleRead >= 20)) {
                lastRuleRead = ticks;
                ruleRead = server.submit(() -> server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY));
            }
            if (ruleRead != null && ruleRead.isDone()) { keepInventory = ruleRead.join(); ruleRead = null; }
            if (keepInventory == null) return false;
        }
        if (!keepInventory) finish("keepInventory is disabled or unconfirmed; no further suicide movement was submitted.", TaskState.FAILED);
        return keepInventory;
    }

    private TaskState abandon(String reason) {
        // 一处危险不可达或未奏效，只放弃该处并记录事实；已发生的烧伤、坠伤与原生爆炸不回滚。
        attempts.set(attempts.size() - 1, Map.of("method", candidate.method(), "outcome", reason));
        attempted.add(candidate.key()); stopNavigation(); InputDriver.halt(player); candidate = null; acting = false;
        return TaskState.RUNNING;
    }

    @Override public boolean observeDeath(LocalPlayer body) {
        if (body != player || !armed || ended || !player.isDeadOrDying()) return false;
        // 最后一击在普通 tick 之前结束身体，也要把最后这段已观察到的血量下降计入回执。
        healthLost += Math.max(0, previousHealth - player.getHealth());
        deathObserved = true;
        finish("Native player death observed; respawn completion is reported separately.", TaskState.SUCCESS);
        return true;
    }

    private TaskState finish(String message, TaskState state) {
        detail = message; ended = true; cleanup(); return state;
    }

    private void stopNavigation() {
        PlayerNav previous = nav; nav = null;
        if (previous != null) previous.stop();
    }

    // 路线收尾异常也必须松键，不能把暂停、超时或失败变成持续冲向危险。
    private void cleanup() { try { stopNavigation(); } finally { release(); } }

    private void release() {
        // 取消或死亡可能发生在动作上下文之外；只释放本任务旧身体持有的输入，不向新身体发送任何动作。
        ClientRuntime.actor().activeContext().filter(context -> context.player() == player)
                .ifPresent(context -> context.body().releaseAll());
        if (ClientRuntime.actor().activeContext().isEmpty() && Minecraft.getInstance().player == player)
            ClientRuntime.actor().body().releaseAll();
    }

    @Override public void stop(LocalPlayer body, StopReason reason) {
        cleanup();
        if (reason != StopReason.PREEMPTED) { ended = true; detail = "Suicide stopped before confirmed death: " + reason; }
        // 暂停后危险和怪物可能变化，恢复时先重新接近；总执行预算与已受伤事实保留。
        acting = false;
    }

    @Override public boolean suppressesSurvivalReflexes() { return !ended; }
    @Override public String name() { return "suicide"; }
    @Override public TaskResult result(TaskState terminal) {
        ended = true; cleanup();
        return new TaskResult(deathObserved && terminal == TaskState.SUCCESS, detail,
                terminal == TaskState.TIMEOUT, terminal == TaskState.CANCELLED, progress());
    }

    @Override public Map<String, Object> progress() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("task", name()); facts.put("method", candidate == null ? request.method() : candidate.method());
        facts.put("phase", ended ? "finished" : acting ? "exposing_to_hazard" : candidate == null ? "observing" : "approaching");
        facts.put("keep_inventory_confirmed", Boolean.TRUE.equals(keepInventory)); facts.put("rule_evidence", ruleSource);
        facts.put("survival_reflexes_suppressed", !ended); facts.put("death_observed", deathObserved);
        facts.put("respawn_observed", false); facts.put("health_lost", healthLost); facts.put("execution_ticks", ticks);
        facts.put("attempts", List.copyOf(attempts)); facts.put("mechanical_retry_allowed", false);
        return facts;
    }
}
