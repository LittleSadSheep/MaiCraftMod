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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.GuiPreparation;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.task.inventory.UnequipCompanionTask;
import org.maiwithu.maicraft.core.task.inventory.UnequipTaskRecord;
import org.maiwithu.maicraft.core.task.suicide.SuicideSelfHazard.Kind;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

/** 确认死亡不掉落 -> 脱下护甲、收起手上的不死图腾 -> 找危险（没有现成危险时随身点火或倒岩浆） -> 原生走近并受伤 -> 观察死亡 -> 登记护甲待穿回；只控制按键与原生物品操作，死亡和重生由原生生命周期结算。 */
public final class SuicideTask implements Task {
    // 寻死前依次处理的栏位：先从头到脚脱护甲，再收副手和主手上的不死图腾，免得图腾把这次死亡挡掉。
    private static final List<EquipmentSlot> STOW = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND, EquipmentSlot.MAINHAND);
    private final LocalPlayer player;
    private final SuicideRequest request;
    private final GuiPreparation gui = new GuiPreparation();
    private final Set<String> attempted = new HashSet<>();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private final List<String> armorRemoved = new ArrayList<>(), armorStillWorn = new ArrayList<>();
    private final List<String> totemsStowed = new ArrayList<>(), totemsStillHeld = new ArrayList<>();
    // 本次确认脱下的护甲原件，寻死结束时交给穿回流程；不含图腾，图腾留在背包里。
    private final Map<EquipmentSlot, ItemStack> stowedArmor = new LinkedHashMap<>();
    private final Map<String, Integer> selfMadeConfirmed = new LinkedHashMap<>();
    private SuicideHazards survey;
    private SuicideHazards.Candidate candidate;
    private PlayerNav nav;
    private SuicideSelfHazard making;
    private UnequipCompanionTask undress;
    private CompletableFuture<Boolean> ruleRead;
    private Boolean keepInventory;
    private int ticks, lastRuleRead, lastProgress, enteredTicks, stowIndex;
    private ItemStack stowing = ItemStack.EMPTY;
    private float previousHealth, healthLost;
    private boolean acting, armed, ended, deathObserved, handedOver;
    private String detail = "Seeking a native death opportunity.";
    private String ruleSource = "unconfirmed";

    public SuicideTask(LocalPlayer player, SuicideTaskRecord record) {
        this.player = player; this.request = record.request;
    }

    @Override public TaskState tick(LocalPlayer body) {
        try { return advance(body); }
        catch (RuntimeException failure) {
            // 规则读取、界面或导航异常也走同一松键出口，回执保留真实错误，不能留着“仍在寻死”的旧说明。
            return finish("Native suicide execution stopped: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage(), TaskState.FAILED);
        }
    }

    private TaskState advance(LocalPlayer body) {
        // 先承认已观察到的本轮死亡，再检查总预算；规则等待、关界面、扫描和移动都计入实际执行刻。
        if (ended) return deathObserved ? TaskState.SUCCESS : TaskState.FAILED;
        if (body != player || player.isRemoved()) return finish("The original body is unavailable.", TaskState.CANCELLED);
        if (observeDeath(body)) return TaskState.SUCCESS;
        if (++ticks > request.timeoutSeconds() * 20) return finish("Timed out without observing death.", TaskState.TIMEOUT);
        // 模式与真实规则不符合用途时停止；多人客户端未同步的 GameRules 不能当作服务器证据。
        if (player.isCreative() || player.isSpectator() || player.level().getLevelData().isHardcore())
            return finish("Suicide requires a non-hardcore survival or adventure body.", TaskState.FAILED);
        if (!checkRule()) return ended ? TaskState.FAILED : TaskState.RUNNING;
        // 先等在途菜单操作并尝试原生关页返料，普通聊天框可保留；角色仍睡在床上时只等待自然醒来。
        // 卸甲子任务进行中由它自己管理打开的背包，不能让界面准备在半途把它关掉。
        if (undress == null && !gui.ready(ClientRuntime.requireContext(player), true)) return TaskState.RUNNING;
        if (!undressed()) return TaskState.RUNNING;
        float health = player.getHealth();
        if (previousHealth > health) { healthLost += previousHealth - health; lastProgress = ticks; }
        previousHealth = health;
        if (survey == null) survey = new SuicideHazards(player, request);
        if (candidate == null) {
            if (!survey.scan()) return TaskState.RUNNING;
            candidate = survey.choose(player, attempted);
            if (candidate == null) return finish(exhausted(), TaskState.FAILED);
            attempts.add(Map.of("method", candidate.method(), "attempt", attempts.size() + 1));
            lastProgress = ticks; enteredTicks = 0; acting = false;
        }
        Vec3 destination = candidate.destination(player);
        if (destination == null || NavigationSafetyContext.forbidsBody(BlockPos.containing(destination))) return abandon("The hazard disappeared or left the permitted area.");
        // 选中危险后，接近阶段也受 400 个执行刻的掉血窗口约束；单纯走得更近不会续这个窗口。
        // 开始直接接触危险时会重新计时，之后任何已观察到的生命值下降都可续期，并不证明选中的危险造成伤害。
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
                // 开始接近就登记这具身体已在尝试寻死；之后的成功证据是本人死亡，而不是推测某只怪必然会打死她。
                armed = true;
                if (nav.tick() == PlayerNav.Status.FAILED) return abandon("Native approach navigation failed: " + nav.failReason());
                return TaskState.RUNNING;
            }
            // 先完整交回普通导航，再按前进踏入危险，避免导航防摔或绕开岩浆的习惯抵消本次意图。
            if (nav != null && !nav.isSafeToCancel()) { nav.tick(); return TaskState.RUNNING; }
            stopNavigation(); acting = true; lastProgress = ticks;
        }
        armed = true; enteredTicks++;
        Kind kind = Kind.of(candidate.method());
        if (kind != null) return expose(kind);
        // 踏出后重新落地且仍存活，只放弃这一入口并尝试下一处，不把“已经跳下去”记作完成。
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

    private boolean undressed() {
        // 先从头到脚把护甲、再把手上的不死图腾逐件交给原生背包收起；每件单独结算，
        // 一件收不走（没空位、绑定诅咒或界面未确认）只记下仍在身上，不影响其余栏位和寻死本身。
        if (undress != null) {
            TaskState state = undress.tick(player);
            if (!state.isTerminal()) return false;
            EquipmentSlot slot = STOW.get(stowIndex++);
            var result = undress.result(state); undress = null;
            // 以原生回执之后栏位里的实际物品为准：护甲栏空了、手上不再是图腾才算收走。
            boolean hand = slot.getType() == EquipmentSlot.Type.HAND;
            boolean moved = hand ? !totem(player.getItemBySlot(slot)) : player.getItemBySlot(slot).isEmpty();
            String label = BuiltInRegistries.ITEM.getKey(stowing.getItem()).getPath() + " (" + slot.getName() + ")";
            String reason = result.success() ? "no free main-inventory slot" : result.message();
            if (moved && !hand) stowedArmor.put(slot, stowing.copy());
            (hand ? moved ? totemsStowed : totemsStillHeld : moved ? armorRemoved : armorStillWorn).add(moved ? label : label + ": " + reason);
            stowing = ItemStack.EMPTY;
            return false;
        }
        while (stowIndex < STOW.size() && !stowable(STOW.get(stowIndex))) stowIndex++;
        if (stowIndex >= STOW.size()) return true;
        // 新建子任务的这一刻只登记，下一刻再让它打开背包，不和本刻刚结清的界面准备挤在同一刻。
        EquipmentSlot slot = STOW.get(stowIndex);
        stowing = player.getItemBySlot(slot).copy();
        undress = new UnequipCompanionTask(player, new UnequipTaskRecord("suicide-stow",
                player.level().getGameTime() + 200, List.of(slot), slot.getName()));
        undress.start(player);
        return false;
    }

    private boolean stowable(EquipmentSlot slot) {
        // 护甲栏有东西就脱；主手和副手只收不死图腾，其他手持物品照旧留着。
        var stack = player.getItemBySlot(slot);
        return slot.getType() == EquipmentSlot.Type.HAND ? totem(stack) : !stack.isEmpty();
    }

    private static boolean totem(ItemStack stack) { return stack.is(Items.TOTEM_OF_UNDYING); }

    private void handOver() {
        // 寻死结束（死亡、失败、超时或取消）时只交接一次：把确认脱下的护甲原件交给穿回流程，重生后或仍活着时自动穿回。
        if (handedOver) return;
        handedOver = true;
        SuicideArmorRestore.remember(player, stowedArmor);
    }

    private TaskState expose(Kind kind) {
        BlockPos cell = candidate.entry();
        // 造危险的回执未结清前只轮询这一次原生使用；结清后按实际危险格或扣桶计数，不把未确认的点击冒称为成功。
        if (making != null) {
            TaskState state = making.tick(ClientRuntime.requireContext(player));
            if (state == TaskState.RUNNING) return TaskState.RUNNING;
            boolean submitted = making.submitted(); String reason = making.failure(); closeMaking();
            if (state == TaskState.SUCCESS || kind.present(player.level(), cell)) {
                selfMadeConfirmed.merge(kind.method, 1, Integer::sum); return TaskState.RUNNING;
            }
            // 已经出手却没出效果，说明原生拒绝或结果未知，之后不再换格反复尝试这种方式；还没出手的瞄准失败只放弃这一格。
            if (submitted) attempted.add(kind.rejectedKey());
            return abandon("Native " + kind.method + " use did not produce its hazard: " + reason);
        }
        // 身体被挤出危险格时先走回格中央，再判断是否需要重新造危险；低头操作要求射线全程留在这一列。
        if (!player.blockPosition().equals(cell)) { InputDriver.stepToward(player, Vec3.atBottomCenterOf(cell), false); return TaskState.RUNNING; }
        InputDriver.halt(player);
        // 危险还在就站着承受原生伤害；火自然熄灭或岩浆被冲走后原地再造，物品用尽且身上也不再着火才放弃这一格。
        if (kind.present(player.level(), cell)) return TaskState.RUNNING;
        if (kind.slot(player.getInventory()) < 0)
            return player.isOnFire() ? TaskState.RUNNING : abandon("No " + kind.label + " remains for another native " + kind.method + " use.");
        making = new SuicideSelfHazard(kind, cell);
        return TaskState.RUNNING;
    }

    private String exhausted() {
        // 没有候选时按每种随身方式说明是“没带物品”“已被原生拒绝”还是“附近没有安全格”，让模型知道该补物品、换地方还是换方式。
        var message = new StringBuilder("No remaining reachable native hazard was found in the loaded search area.");
        for (Kind kind : Kind.values()) {
            if (!request.permits(kind.method)) continue;
            message.append(' ').append(attempted.contains(kind.rejectedKey()) ? "Native " + kind.method + " use was already rejected or left unconfirmed."
                    : kind.slot(player.getInventory()) < 0 ? "No " + kind.label + " is carried for " + kind.method + "."
                    : "No untried air cell on a sturdy floor clear of flammable or protected blocks was found for " + kind.method + ".");
        }
        return message.toString();
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
            // 首次规则读取未返回前不走向危险；这段等待仍占用本任务预算，不能把未知规则当作已经开启。
            if (keepInventory == null) return false;
        }
        if (!keepInventory) finish("keepInventory is disabled or unconfirmed; no further suicide movement was submitted.", TaskState.FAILED);
        return keepInventory;
    }

    private TaskState abandon(String reason) {
        // 一处危险不可达或未奏效，只放弃该处并记录事实；已发生的烧伤、坠伤与原生爆炸不回滚。
        // 替换本次尝试的结论后记住入口或怪物 UUID，后续从原地形候选和新观察的活怪中另选，不全量重扫地形。
        attempts.set(attempts.size() - 1, Map.of("method", candidate.method(), "outcome", reason));
        attempted.add(candidate.key()); closeMaking(); stopNavigation(); InputDriver.halt(player); candidate = null; acting = false;
        return TaskState.RUNNING;
    }

    @Override public boolean observeDeath(LocalPlayer body) {
        // 必须是已开始尝试的同一具身体报告死亡；取消、失联或换成新身体都不能补造成功，也不在这里请求重生。
        if (body != player || !armed || ended || !player.isDeadOrDying()) return false;
        // 最后一击在普通 tick 之前结束身体，也要把最后这段已观察到的血量下降计入回执。
        healthLost += Math.max(0, previousHealth - player.getHealth());
        deathObserved = true;
        finish("Native player death observed; respawn completion is reported separately.", TaskState.SUCCESS);
        return true;
    }

    private TaskState finish(String message, TaskState state) {
        detail = message; ended = true; cleanup(); handOver(); return state;
    }

    private void stopNavigation() {
        PlayerNav previous = nav; nav = null;
        if (previous != null) previous.stop();
    }

    private void closeMaking() {
        // 结束等待只释放动作槽，已经发出的点火或倒桶结果仍以世界里的危险格为准，不能当作撤销。
        SuicideSelfHazard previous = making; making = null;
        if (previous != null) previous.close(player);
    }

    private void closeUndress() {
        // 卸甲中途暂停或结束时收掉子任务的背包会话；这件护甲恢复后重新检查，不把未确认的搬运记成已脱下。
        UnequipCompanionTask previous = undress; undress = null;
        if (previous != null) { previous.stop(player, StopReason.REPLACED); previous.result(TaskState.CANCELLED); }
    }

    // 路线收尾异常也必须松键，不能把暂停、超时或失败变成持续冲向危险。
    private void cleanup() { try { closeUndress(); closeMaking(); stopNavigation(); } finally { release(); } }

    private void release() {
        // 取消或死亡可能发生在动作上下文之外；只释放本任务旧身体持有的输入，不向新身体发送任何动作。
        ClientRuntime.actor().activeContext().filter(context -> context.player() == player)
                .ifPresent(context -> context.body().releaseAll());
        if (ClientRuntime.actor().activeContext().isEmpty() && Minecraft.getInstance().player == player)
            ClientRuntime.actor().body().releaseAll();
    }

    @Override public void stop(LocalPlayer body, StopReason reason) {
        // 临时让出身体只撤移动，恢复后重查并重新接近；取消或旧身体消失则封存本次尝试，不能沿用到新玩家对象。
        cleanup();
        if (reason != StopReason.PREEMPTED) { ended = true; detail = "Suicide stopped before confirmed death: " + reason; handOver(); }
        // 暂停后危险和怪物可能变化，恢复时先重新接近；总执行预算与已受伤事实保留。
        acting = false;
    }

    @Override public boolean suppressesSurvivalReflexes() { return !ended; }
    @Override public String name() { return "suicide"; }

    /** 面板行动行的一句话汇报；阶段与 progress() 的 phase 字段同源，方式来自已选定的危险候选。 */
    @Override
    public String describeCurrentAction() {
        if (ended) return "寻死流程已结束";
        if (undress != null) return totem(stowing) ? "正在收起不死图腾" : "正在脱下护甲";
        if (candidate == null) return "正在观察附近寻找危险";
        return switch (candidate.method()) {
            case "lava" -> acting ? "正在站在岩浆中" : "正在走向岩浆";
            case "hostile" -> acting ? "正在让怪物攻击" : "正在接近怪物";
            case "fall" -> acting ? "正在踏入高处边缘" : "正在走向高处边缘";
            case "fire" -> acting ? "正在原地点火燃烧" : "正在走向点火位置";
            case "lava_bucket" -> acting ? "正在站在倒出的岩浆中" : "正在走向倒岩浆位置";
            default -> acting ? "正在暴露在危险中" : "正在走向危险处";
        };
    }

    @Override public TaskResult result(TaskState terminal) {
        // 寻死的成功只确认死亡；重生请求和重生后背包观察由公共生命周期另发回执，不能在这里提前承诺。
        ended = true; cleanup(); handOver();
        return new TaskResult(deathObserved && terminal == TaskState.SUCCESS, detail,
                terminal == TaskState.TIMEOUT, terminal == TaskState.CANCELLED, progress());
    }

    @Override public Map<String, Object> progress() {
        // 回执保留每次放弃的真实原因与累计观察到的掉血；不输出操作坐标，也不把尝试次数或存活坠落包装成死亡。
        // 子任务的保护字段表达自身执行意图，父任务查询会按暂停、终态和步骤切换覆盖为实际调度资格。
        var facts = new LinkedHashMap<String, Object>();
        facts.put("task", name()); facts.put("method", candidate == null ? request.method() : candidate.method());
        facts.put("phase", ended ? "finished" : undress != null ? "stowing_protection" : acting ? "exposing_to_hazard"
                : candidate == null ? "observing" : "approaching");
        facts.put("keep_inventory_confirmed", Boolean.TRUE.equals(keepInventory)); facts.put("rule_evidence", ruleSource);
        facts.put("survival_reflexes_suppressed", !ended); facts.put("death_observed", deathObserved);
        facts.put("respawn_observed", false); facts.put("health_lost", healthLost); facts.put("execution_ticks", ticks);
        facts.put("ignitions_confirmed", selfMadeConfirmed.getOrDefault(Kind.FIRE.method, 0));
        facts.put("lava_pours_confirmed", selfMadeConfirmed.getOrDefault(Kind.LAVA_BUCKET.method, 0));
        // 护甲和图腾是否真的收走以原生背包回执后的栏位为准；没空位、绑定诅咒或界面未确认而仍在身上的单独列出。
        facts.put("armor_removed", List.copyOf(armorRemoved)); facts.put("armor_still_worn", List.copyOf(armorStillWorn));
        facts.put("totems_stowed", List.copyOf(totemsStowed)); facts.put("totems_still_held", List.copyOf(totemsStillHeld));
        facts.put("attempts", List.copyOf(attempts)); facts.put("mechanical_retry_allowed", false);
        return facts;
    }
}
