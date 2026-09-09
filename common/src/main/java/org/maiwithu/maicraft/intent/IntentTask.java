package org.maiwithu.maicraft.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.ToolRegistry;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import org.maiwithu.maicraft.entity.InputDriver;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.core.integration.create.CreateMechanicalPower;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.InternalPositionReceipt;
import org.maiwithu.maicraft.task.InternalAreaProtectionReceipt;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.task.TaskState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 把“我想做成这件事”一步步做出来，例如先取材料，再建房子。
 * 这个类记住当前做哪一步，并调用负责走路、挖矿等具体动作的任务；遇到做不下去的情况就询问调用者。
 * 调度器只看到这个总任务，里面的小任务由这里推进，不会把总任务挤走。
 */
final class IntentTask implements Task {

    private static final Set<String> INTERNAL_RESULT_KEYS = Set.of(
            "entity_id", "entity_ids", "requested_entity_ids", "defeated_entity_ids",
            "lost_entity_ids", "unreachable_entity_ids", "combat_by_entity",
            "runtime_id", "runtime_ids", "target_runtime_id", "target_runtime_ids",
            "button", "click", "clicks", "slot", "slots", "inventory_slots",
            "slot_clicks", "click_sequence", "route", "waypoints", "path_nodes",
            "block_ops", "placements", "cells", "continuation_token",
            "continuation_prefix_hash", "verified_position", "failure_position",
            "final_position", "site_min", "site_max", "remaining_scaffolds",
            "origin", "observed_loaded_bounds", "explored_centers",
            "observed_unloaded_frontier_samples", "failed_legs",
            "x", "y", "z", "position", "center", "location", "destination", "bounds");

    private final LocalPlayer player;
    private final IntentTaskRecord record;
    private final IntentRuntime runtime;

    private Task child;
    private TaskRecord childRecord;
    private boolean reobserveAfterChild;
    private IntentAction.Wait wait;
    private List<IntentAction.Tool> chain = List.of();
    private int chainIndex;
    private TaskResult terminalResult;
    private TaskResult interruptedChildResult;
    private boolean terminalPublished;
    private long childSerial;
    /** Opaque one-use receipts stay inside the Mod; the model only chooses semantic retry. */
    private final Map<String, UUID> mechanicalContinuations = new LinkedHashMap<>();
    private String cachedProtectionDimension;
    private int cachedProtectionStep = -1;
    private LongSet cachedProtectedMutationCells = LongSets.emptySet();
    private LongSet cachedForbiddenBodyCells = LongSets.emptySet();
    IntentTask(LocalPlayer player, IntentTaskRecord record, IntentRuntime runtime) {
        this.player = player;
        this.record = record;
        this.runtime = runtime;
    }

    @Override
    public boolean canRun(LocalPlayer ignored) {
        // 总任务被暂停时不主动争取执行机会；能否马上停下，还要由外层判断当前动作是否安全。
        return !record.paused();
    }

    @Override
    public TaskState tick(LocalPlayer ignored) {
        try {
            return tickSemanticParent();
        } catch (RuntimeException failure) {
            // A malformed adapter result or unexpected internal capability failure must
            // still enter the semantic recovery protocol. Fatal VM errors are deliberately
            // not caught here.
            abandonChildAfterUnexpectedFailure();
            wait = null;
            return failStep(TaskState.FAILED,
                    TaskResult.fail("semantic step failed safely: " + safeMessage(failure)));
        } finally {
            // Attention and explicit inspection see the actual observed active child.
            // Diagnostic failures must never interrupt physical work or replace its real result.
            try { record.observeExecution(progress(), player.level().getGameTime()); }
            catch (RuntimeException ignoredDiagnosticFailure) { }
        }
    }

    private TaskState tickSemanticParent() {
        // 所有步骤都记为完成后，才报告整个目标成功。
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = successResult();
            return TaskState.SUCCESS;
        }

        IntentTaskRecord.DecisionAnswer answer = record.takeAnswer();
        // 先处理调用者刚给的答复：取消、跳过、先补一个条件，或者修改目标后继续。
        if (answer != null) {
            if ("cancel".equals(answer.choice()) || "cancel_task".equals(answer.choice())) {
                mechanicalContinuations.clear();
                terminalResult = TaskResult.cancelled("cancelled at decision " + answer.decisionId());
                return TaskState.CANCELLED;
            }
            if ("respawn".equals(answer.choice()) || "spectate".equals(answer.choice())) {
                // Death-screen actions are executed natively by the runtime facade.  Once a
                // restored task is explicitly resumed, re-evaluate its semantic step instead of
                // passing a transport/lifecycle answer to an ability adapter.
                return TaskState.RUNNING;
            }
            if ("skip".equals(answer.choice())) {
                // “跳过”也会把这一步记为已处理，结果文字注明是人为跳过，并不说明游戏里真的做成了。
                discardContinuation(currentGoal());
                completeStep(TaskResult.ok("step skipped by explicit decision"));
                return record.stepIndex() >= record.steps().size() ? TaskState.SUCCESS : TaskState.RUNNING;
            }
            if ("recover".equals(answer.choice()) || "replace_goal".equals(answer.choice())) {
                if ("replace_goal".equals(answer.choice())) discardContinuation(currentGoal());
                return applySemanticAnswer(answer);
            }
            IntentAction resolved = AbilityAdapter.fromAnswer(
                    resolvedCurrentGoal(), answer, player, runtime,
                    continuationFor(currentGoal()), currentFailureResult());
            return begin(resolved);
        }

        // 正在走路就继续走这条路，不能每一刻都重新创建“去目的地”的任务。
        if (child != null) {
            return tickChild();
        }
        if (wait != null) {
            return tickWait();
        }
        if (!chain.isEmpty()) {
            return beginTool(chain.get(chainIndex));
        }

        Goal semanticGoal = currentGoal();
        return begin(AbilityAdapter.adapt(
                resolvedCurrentGoal(), player, runtime, continuationFor(semanticGoal)));
    }

    private TaskState begin(IntentAction action) {
        // 目标转换后可能是“还在准备”“直接给出结果”“做一项动作”“等待条件”或“需要询问”。
        if (action == IntentAction.Pending.INSTANCE) return TaskState.RUNNING;
        if (action instanceof IntentAction.Report report) {
            if (!report.result().success()) return failStep(TaskState.FAILED, report.result());
            if (report.verifiedPosition() != null) {
                record.retainInternalStepPosition(record.stepIndex(), report.verifiedPosition());
            }
            completeStep(report.result());
            return afterImmediate();
        }
        if (action instanceof IntentAction.Native nativeAction) {
            // 某些动作只是为了看清情况，例如靠近电梯读楼层；做完还得回来重新判断原目标。
            reobserveAfterChild=nativeAction.reobserveAfterSuccess();
            return beginNative(nativeAction.record());
        }
        if (action instanceof IntentAction.Chain nextChain) {
            // 一组内部动作按顺序做，记住做到第几个，每次只启动当前那个。
            chain = nextChain.actions();
            chainIndex = 0;
            return beginTool(chain.getFirst());
        }
        if (action instanceof IntentAction.Decision decision) {
            return requestDecision(decision.snapshot());
        }
        if (action instanceof IntentAction.Remember remember) {
            runtime.remember(remember.label(), remember.position(), remember.areaRole());
            record.retainInternalStepPosition(record.stepIndex(), remember.position());
            completeStep(TaskResult.ok("remembered " + remember.label(),
                    Map.of("label", remember.label(),
                            "area_role", remember.areaRole().id())));
            return afterImmediate();
        }
        if (action instanceof IntentAction.Wait nextWait) {
            wait = nextWait;
            return tickWait();
        }
        if (action instanceof IntentAction.Tool toolAction) {
            return beginTool(toolAction);
        }
        return failStep(
                TaskState.FAILED,
                TaskResult.fail("intent adapter produced no executable action"));
    }

    private TaskState beginTool(IntentAction.Tool action) {
        // 根据内部工具名找到实现，例如 goto 对应移动；缺少实现时报告失败，不假装接单成功。
        MaiCraftTool tool = ToolRegistry.resolve(action.toolName());
        if (tool == null) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("required internal capability is not registered: "
                            + action.toolName()));
        }

        AtomicReference<TaskRecord> captured = new AtomicReference<>();
        AtomicReference<String> immediate = new AtomicReference<>();
        String childCallId = "intent-" + record.externalId() + "-"
                + record.stepIndex() + "-" + (++childSerial);
        try {
            withExplicitAreaProtection(() -> {
                // 工具原本会把任务交给总调度器；这里先接住，把它当作总任务里的小步骤执行。
                TaskDispatch.captureNext(captured::set, () ->
                        tool.onGameCall(childCallId, action.arguments(), player, immediate::set));
                return null;
            });
        } catch (RuntimeException exception) {
            return failStep(
                    TaskState.FAILED,
                    TaskResult.fail("ability " + currentGoal().ability()
                            + " could not start: " + safeMessage(exception)));
        }

        if (captured.get() != null) {
            // 工具交回一张任务单，说明还要在游戏里继续做；立即返回的文字不能当作已完成。
            return beginNative(captured.get());
        }

        if (immediate.get() != null) {
            TaskResult result = parseImmediate(immediate.get());
            if (!result.success()) return failStep(TaskState.FAILED, result);
            return finishToolSuccess(result);
        }

        return failStep(
                TaskState.FAILED,
                TaskResult.fail("internal capability produced neither a child task nor a result: "
                        + action.toolName()));
    }

    private TaskState beginNative(TaskRecord nextRecord) {
            retainBuildProject(nextRecord);
            // 记住这一步的任务单，创建对应执行代码，只做第一次准备；后续每刻继续同一个对象。
            childRecord = nextRecord;
            childRecord.setState(TaskState.RUNNING);
            childRecord.markStarted(player.level().getGameTime());
            child = withExplicitAreaProtection(() -> TaskFactory.create(player, childRecord));
            try {
                withExplicitAreaProtection(() -> {
                    child.start(player);
                    return null;
                });
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                try {
                    child.result(TaskState.FAILED);
                } catch (RuntimeException ignoredFailure) {
                }
                TaskResult failure = TaskResult.fail(
                        "child start failed: " + safeMessage(exception));
                clearChild();
                return failStep(TaskState.FAILED, failure);
            }
            if (childRecord.getState().isTerminal()) {
                return finishChild();
            }
            return TaskState.RUNNING;
    }

    private TaskState tickChild() {
        // 先检查小任务自己的截止时间。总任务暂停时，这个时间目前不会一起往后推。
        if (player.level().getGameTime() >= childRecord.getDeadlineGameTime()) {
            childRecord.setState(TaskState.TIMEOUT);
        } else {
            try {
                childRecord.setState(withExplicitAreaProtection(() -> child.tick(player)));
            } catch (RuntimeException exception) {
                childRecord.setState(TaskState.FAILED);
                childRecord.setResult(TaskResult.fail(
                        "child tick failed: " + safeMessage(exception)));
            }
        }
        retainBuildProject(childRecord);
        return childRecord.getState().isTerminal() ? finishChild() : TaskState.RUNNING;
    }

    private void retainBuildProject(TaskRecord child) {
        var plan = child instanceof org.maiwithu.maicraft.core.task.build.BuildTaskRecord build ? build
                : child instanceof org.maiwithu.maicraft.core.task.supply.SemanticBuildSupplyTaskRecord supply ? supply.plan
                : child instanceof org.maiwithu.maicraft.core.task.build.BuildSiteInvestigationTaskRecord site ? site.projectPlan()
                : null;
        if (plan != null && record.retainBuildProject(plan.projectId(), plan.projectProtectionLabels())) {
            invalidateProtectionCache();
        }
    }

    private TaskState finishChild() {
        // 小任务做完后先收结果、松开按键，再决定做下一步还是询问；“材料取完了”还不等于“房子建好了”。
        Task finishingChild = child;
        TaskRecord finishingRecord = childRecord;
        boolean reobserve=reobserveAfterChild;
        TaskState state = finishingRecord.getState();
        TaskResult result;
        try {
            TaskState finalState = state;
            result = withExplicitAreaProtection(() -> finishingChild.result(finalState));
        } catch (RuntimeException exception) {
            result = TaskResult.fail("child result failed: " + safeMessage(exception));
            state = TaskState.FAILED;
        } finally {
            releaseBody();
            if (child == finishingChild) clearChild();
        }
        if (result == null) result = defaultResult(state);
        // 如果只是靠近电梯读到了楼层，就重新判断该去哪层，不能把“读到楼层”当成“已到目的地”。
        if(result.success() && reobserve) return TaskState.RUNNING;
        if (finishingRecord instanceof org.maiwithu.maicraft.core.task.build.BuildTaskRecord
                && MachineAbilityAdapter.supports(currentGoal().ability())) {
            // 机器方块搭好了，只能证明外形完成；不能据此说机器已经通电、运转或产出了物品。
            Map<String, Object> machineData = new LinkedHashMap<>(result.data());
            machineData.put("machine_geometry_verified", result.success());
            machineData.put("machine_production_verified", false);
            machineData.put("verification_scope", "Mod-compiled physical targets and confirmed native effects; inspect menus, interfaces and actual production separately");
            result = new TaskResult(result.success(), result.message(), result.timedOut(), result.interrupted(), machineData);
        }
        InternalPositionReceipt.Position internalPosition = result.success()
                && finishingRecord instanceof InternalPositionReceipt receipt
                ? receipt.internalVerifiedPosition() : null;
        if (internalPosition != null) {
            record.retainInternalStepPosition(record.stepIndex(), new Goal.WorldPosition(
                    internalPosition.x(), internalPosition.y(), internalPosition.z(),
                    internalPosition.dimension()));
        }
        if (result.success()
                && finishingRecord instanceof InternalAreaProtectionReceipt receipt) {
            record.retainInternalAreaProtections(
                    record.stepIndex(), receipt.internalAreaProtections());
            invalidateProtectionCache();
        }
        if (!result.success()) {
            return failStep(state, result);
        }
        return finishToolSuccess(result);
    }

    private TaskState finishToolSuccess(TaskResult result) {
        // 内部动作串还没做完就继续下一个；全部成功后，才把这一个目标步骤记为完成。
        if (!chain.isEmpty()) {
            chainIndex++;
            if (chainIndex < chain.size()) return TaskState.RUNNING;
            chain = List.of();
            chainIndex = 0;
        }
        discardContinuation(currentGoal());
        completeStep(result);
        return afterImmediate();
    }

    private TaskState applySemanticAnswer(IntentTaskRecord.DecisionAnswer answer) {
        // recover 表示“先做这件事，再重试原任务”；replace_goal 表示“原来这一步改做别的”。
        JsonObject details = answer.details();
        if (!details.has("goal") || !details.get("goal").isJsonObject()) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), answer.choice() + " requires details.goal",
                    currentFailureResult()));
        }
        Goal semanticGoal;
        try {
            semanticGoal = Goal.fromJson(details.getAsJsonObject("goal"));
            runtime.validateGoal(semanticGoal);
        } catch (RuntimeException exception) {
            return requestDecision(RecoveryAdvisor.invalidSemanticAnswer(
                    currentGoal(), safeMessage(exception), currentFailureResult()));
        }
        if ("recover".equals(answer.choice())) {
            record.discardInternalStepPosition(record.stepIndex());
            record.insertRecovery(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, false);
        } else {
            record.discardInternalStepPosition(record.stepIndex());
            record.replaceCurrent(semanticGoal);
            runtime.semanticPlanChanged(record, semanticGoal, true);
        }
        invalidateProtectionCache();
        return TaskState.RUNNING;
    }

    private TaskState failStep(TaskState state, TaskResult result) {
        // 记下这次为什么失败，再询问接下来怎么办；保留总目标，等待重试、补条件、换目标或取消。
        TaskState failureState = state == null ? TaskState.FAILED : state;
        // A failed/abandoned execution can never authorize a later prior_result binding.
        record.discardInternalStepPosition(record.stepIndex());
        captureMechanicalContinuation(currentGoal(), result);
        TaskResult failure = withEffectLedger(semanticResult(
                result == null ? TaskResult.fail("internal action failed") : result));
        Goal failedGoal = currentGoal();
        record.addAttempt(new IntentTaskRecord.AttemptSnapshot(
                record.stepIndex(),
                failedGoal,
                failureState,
                failure.message(),
                failure.toJson(),
                player.level().getGameTime()));
        terminalResult = null;
        chain = List.of();
        chainIndex = 0;
        return requestDecision(RecoveryAdvisor.afterFailure(
                failedGoal, failureState, failure));
    }

    /** Add one stable semantic effect ledger to every failed step before it crosses MCP. */
    private TaskResult withEffectLedger(TaskResult failure) {
        // 失败时一起告诉调用者：前面哪些事已经做了，当前和后面还有哪些没做，避免重复开工。
        Map<String, Object> data = new LinkedHashMap<>(failure.data());
        data.put("completed_effects", completedEffects());
        data.put("remaining_effects", remainingEffects());
        return new TaskResult(
                failure.success(), failure.message(), failure.timedOut(), failure.interrupted(),
                Map.copyOf(data));
    }

    private List<Map<String, Object>> completedEffects() {
        List<Map<String, Object>> effects = new ArrayList<>();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("step_index", step.index());
            effect.put("ability", step.ability());
            if (step.index() >= 0 && step.index() < record.steps().size()) {
                effect.put("outcome", sanitizeMessage(record.steps().get(step.index()).outcome()));
            }
            effect.put("success", step.success());
            effect.put("summary", sanitizeMessage(step.message()));
            Object confirmed = sanitizeJson(step.result());
            if (confirmed != null) effect.put("confirmed_effect", confirmed);
            effects.add(Map.copyOf(effect));
        }
        return List.copyOf(effects);
    }

    private List<Map<String, Object>> remainingEffects() {
        List<Map<String, Object>> effects = new ArrayList<>();
        for (int index = record.stepIndex(); index < record.steps().size(); index++) {
            Goal pending = record.steps().get(index);
            Map<String, Object> effect = new LinkedHashMap<>();
            effect.put("step_index", index);
            effect.put("state", index == record.stepIndex() ? "failed_current" : "pending");
            effect.put("ability", pending.ability());
            effect.put("outcome", sanitizeMessage(pending.outcome()));
            effects.add(Map.copyOf(effect));
        }
        return List.copyOf(effects);
    }

    /** Return only the failed result for this exact still-current semantic step. */
    private JsonObject currentFailureResult() {
        // 只有“当前这一步、当前这个目标”的失败才能拿来决定重试；改过目标后不能误用旧原因。
        List<IntentTaskRecord.AttemptSnapshot> attempts = record.attempts();
        if (attempts.isEmpty() || record.stepIndex() >= record.steps().size()) return null;
        IntentTaskRecord.AttemptSnapshot latest = attempts.getLast();
        Goal current = record.steps().get(record.stepIndex());
        if (latest.stepIndex() != record.stepIndex()
                || !latest.goal().toJson().equals(current.toJson())) {
            return null;
        }
        return latest.result();
    }

    private void captureMechanicalContinuation(Goal goal, TaskResult raw) {
        // 机械连接可能已经装好一部分。记住内部给的恢复编号，下次重试才能核对并接着做，避免重复装配。
        if (goal == null || !("maicraft:connect_mechanical_power".equals(goal.ability())
                || (MachineAbilityAdapter.MODIFY.equals(goal.ability())
                    && goal.parameters().has("operation")
                    && "connect_mechanical_power".equals(goal.parameters().get("operation").getAsString())))) return;
        String key = continuationKey(goal);
        Object value = raw == null || raw.data() == null
                ? null : raw.data().get("continuation_token");
        if (value == null) {
            CreateMechanicalPower.discardContinuation(mechanicalContinuations.remove(key));
            return;
        }
        try {
            UUID next = UUID.fromString(String.valueOf(value));
            UUID prior = mechanicalContinuations.put(key, next);
            if (!next.equals(prior)) CreateMechanicalPower.discardContinuation(prior);
        } catch (IllegalArgumentException invalid) {
            CreateMechanicalPower.discardContinuation(mechanicalContinuations.remove(key));
        }
    }

    private UUID continuationFor(Goal goal) {
        return goal == null ? null : mechanicalContinuations.get(continuationKey(goal));
    }

    private void discardContinuation(Goal goal) {
        if (goal != null) CreateMechanicalPower.discardContinuation(
                mechanicalContinuations.remove(continuationKey(goal)));
    }

    private static String continuationKey(Goal goal) {
        return goal.toJson().toString();
    }

    private TaskState requestDecision(IntentTaskRecord.DecisionSnapshot decision) {
        record.requestDecision(decision, player.level().getGameTime());
        runtime.decision(record, decision);
        return TaskState.RUNNING;
    }

    private TaskState tickWait() {
        // 先等到允许检查的时间，再看天色、血量或饱食度；条件没满足就继续等，不主动做其他事。
        if (player.level().getGameTime() < wait.notBeforeGameTime()) return TaskState.RUNNING;
        boolean satisfied = switch (wait.condition()) {
            case "elapsed" -> true;
            case "day" -> WorldTimeSemantics.isDaytime(player.level());
            case "night" -> WorldTimeSemantics.isNighttime(player.level());
            case "health_full" -> player.getHealth() >= player.getMaxHealth();
            case "not_hungry" -> player.getFoodData().getFoodLevel() >= 18;
            default -> false;
        };
        if (!satisfied) return TaskState.RUNNING;
        String condition = wait.condition();
        wait = null;
        completeStep(TaskResult.ok("wait condition satisfied: " + condition));
        return afterImmediate();
    }

    private TaskState afterImmediate() {
        if (record.stepIndex() >= record.steps().size()) {
            terminalResult = successResult();
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    private void completeStep(TaskResult result) {
        result = semanticResult(result);
        int index = record.stepIndex();
        Goal goal = record.steps().get(index);
        record.addStepResult(new IntentTaskRecord.StepSnapshot(
                index, goal.ability(), result.success(), result.message(), result.toJson()));
        runtime.stepCompleted(record);
        if (!result.success()) terminalResult = result;
    }

    private Goal currentGoal() {
        return record.steps().get(record.stepIndex());
    }

    /**
     * Resolve a semantic prior_result to an authoritative position reported by an earlier task.
     * The LLM names the relationship; it never copies coordinates between steps.
     */
    private Goal resolvedCurrentGoal() {
        // “去前面那一步找到的地方”要换成 Mod 自己确认过的位置，不能只凭文字猜坐标。
        Goal goal = currentGoal();
        Goal.SemanticTarget target = goal.target();
        if (target == null || !"prior_result".equals(target.kind())) return goal;
        Goal.WorldPosition position = reportedPosition(target);
        return position == null ? goal : goal.withTarget(new Goal.SemanticTarget(
                "coordinates", target.label(), position, target.relation()));
    }

    private Goal.WorldPosition reportedPosition(Goal.SemanticTarget target) {
        // 从已成功的步骤里找位置。只有一个时直接使用；有多个时按名称和描述匹配，打平则不擅自选。
        List<ReportedPosition> candidates = new ArrayList<>();
        List<IntentTaskRecord.StepSnapshot> completed = record.stepResults();
        for (int index = completed.size() - 1; index >= 0; index--) {
            IntentTaskRecord.StepSnapshot step = completed.get(index);
            if (!step.success()) continue;
            Goal.WorldPosition internal = record.internalStepPosition(step.index());
            if (internal != null) {
                Goal sourceGoal = step.index() >= 0 && step.index() < record.steps().size()
                        ? record.steps().get(step.index()) : null;
                candidates.add(new ReportedPosition(step, sourceGoal, internal));
                continue;
            }
            JsonObject root;
            try {
                root = step.result();
            } catch (RuntimeException ignored) {
                continue;
            }
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;
            Goal.WorldPosition position = nestedPosition(data, "verified_position");
            if (position == null) position = nestedPosition(data, "final_position");
            if (position == null) position = nestedPosition(data, "position");
            if (position == null && numbers(data, "final_x", "final_y", "final_z")) {
                position = new Goal.WorldPosition(
                        (int) Math.floor(data.get("final_x").getAsDouble()),
                        (int) Math.floor(data.get("final_y").getAsDouble()),
                        (int) Math.floor(data.get("final_z").getAsDouble()),
                        player.level().dimension().location().toString());
            }
            if (position == null) continue;
            Goal sourceGoal = step.index() >= 0 && step.index() < record.steps().size()
                    ? record.steps().get(step.index()) : null;
            candidates.add(new ReportedPosition(step, sourceGoal, position));
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.getFirst().position();

        String query = ((target.label() == null ? "" : target.label()) + " "
                + (target.relation() == null ? "" : target.relation())).strip();
        int bestScore = 0;
        ReportedPosition best = null;
        boolean ambiguous = false;
        for (ReportedPosition candidate : candidates) {
            int score = semanticScore(query, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                ambiguous = false;
            } else if (score > 0 && score == bestScore
                    && best != null && !best.position().equals(candidate.position())) {
                ambiguous = true;
            }
        }
        // Never silently bind "there" to the most recent unrelated task. If more than one
        // authoritative position exists, the semantic relation must distinguish one of them.
        return bestScore > 0 && !ambiguous ? best.position() : null;
    }

    private static int semanticScore(String query, ReportedPosition candidate) {
        // 这是文字相似度打分，不是另一个大模型：描述、能力名和词片段重合越多，分数越高。
        if (query == null || query.isBlank()) return 0;
        String needle = normalizeSemanticText(query);
        IntentTaskRecord.StepSnapshot step = candidate.step();
        Goal goal = candidate.goal();
        String source = step.ability() + " " + step.message() + " " + step.resultJson();
        if (goal != null) source += " " + goal.toJson();
        String haystack = normalizeSemanticText(source);
        int score = 0;
        if (!needle.isBlank() && haystack.contains(needle)) score += 200;
        if (goal != null) {
            String outcome = normalizeSemanticText(goal.outcome());
            if (!outcome.isBlank() && (needle.contains(outcome) || outcome.contains(needle))) {
                score += 120;
            }
            String ability = goal.ability();
            int colon = ability.indexOf(':');
            String suffix = normalizeSemanticText(colon < 0 ? ability : ability.substring(colon + 1));
            if (!suffix.isBlank() && needle.contains(suffix)) score += 80;
        }
        for (String unit : semanticUnits(needle)) {
            if (haystack.contains(unit)) score += Math.min(24, 4 + unit.length() * 2);
        }
        return score;
    }

    private static List<String> semanticUnits(String normalized) {
        // 英文按空格拆词并排除 there 等泛指词；中文还拆出连续两个字，便于匹配较长描述的一部分。
        List<String> result = new ArrayList<>();
        for (String token : normalized.split(" +")) {
            if (token.length() < 2 || List.of(
                    "the", "that", "there", "prior", "previous", "result", "step",
                    "earlier", "place", "position", "from", "into").contains(token)) continue;
            result.add(token);
            int[] points = token.codePoints().toArray();
            if (points.length >= 2 && java.util.Arrays.stream(points).anyMatch(
                    point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN)) {
                for (int index = 0; index + 1 < points.length; index++) {
                    result.add(new String(points, index, 2));
                }
            }
        }
        return result;
    }

    private static String normalizeSemanticText(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}:_./-]+", " ").strip();
    }

    private record ReportedPosition(
            IntentTaskRecord.StepSnapshot step, Goal goal, Goal.WorldPosition position) {}

    private Goal.WorldPosition nestedPosition(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonObject()) return null;
        JsonObject value = data.getAsJsonObject(key);
        if (!numbers(value, "x", "y", "z")) return null;
        String dimension = value.has("dimension") && value.get("dimension").isJsonPrimitive()
                ? value.get("dimension").getAsString()
                : player.level().dimension().location().toString();
        return new Goal.WorldPosition(
                (int) Math.floor(value.get("x").getAsDouble()),
                (int) Math.floor(value.get("y").getAsDouble()),
                (int) Math.floor(value.get("z").getAsDouble()), dimension);
    }

    private static boolean numbers(JsonObject value, String x, String y, String z) {
        return value.has(x) && value.get(x).isJsonPrimitive()
                && value.has(y) && value.get(y).isJsonPrimitive()
                && value.has(z) && value.get(z).isJsonPrimitive();
    }

    @Override
    public void stop(LocalPlayer ignored, StopReason reason) {
        // 临时暂停只通知小任务停下，保留它做到哪；取消或离开旧玩家则还要收取结果、移走小任务。
        Task stoppingChild = child;
        TaskRecord stoppingRecord = childRecord;
        if (stoppingChild == null) {
            releaseBody();
            return;
        }
        if (reason == StopReason.PREEMPTED) {
            try {
                withExplicitAreaProtection(() -> { stoppingChild.stop(player, reason); return null; });
            } catch (RuntimeException ignoredFailure) {
                // The logical child is deliberately retained for resume.
            } finally {
                releaseBody();
            }
            return;
        }

        try {
            try {
                withExplicitAreaProtection(() -> { stoppingChild.stop(player, reason); return null; });
            } catch (RuntimeException ignoredFailure) {
                // result() below remains the authoritative logical cleanup path.
            }
            if (stoppingRecord != null && !stoppingRecord.getState().isTerminal()) {
                stoppingRecord.setState(TaskState.CANCELLED);
            }
            try {
                TaskState state = stoppingRecord == null
                        ? TaskState.CANCELLED : stoppingRecord.getState();
                TaskResult cleanupResult = withExplicitAreaProtection(() -> stoppingChild.result(state));
                if (stoppingRecord != null) stoppingRecord.setResult(cleanupResult);
                retainInterruptedChild(state, cleanupResult);
            } catch (RuntimeException ignoredFailure) {
                retainInterruptedChild(TaskState.CANCELLED, TaskResult.fail(
                        "Child cleanup could not confirm its effects; inspect before another operation.",
                        Map.of("outcome_uncertain", true, "mechanical_retry_allowed", false)));
            }
        } finally {
            releaseBody();
            if (child == stoppingChild) clearChild();
        }
    }

    @Override
    public TaskResult result(TaskState terminal) {
        // 总任务结束前，如果还有小任务就先停止；把部分完成的情况保留下来，并且只发一次结束消息。
        if (child != null) {
            stop(player, StopReason.REPLACED);
        }
        TaskResult result = terminalResult;
        if (result == null) {
            result = switch (terminal) {
                case SUCCESS -> successResult();
                case TIMEOUT -> TaskResult.timeout("semantic task timed out");
                case CANCELLED -> TaskResult.cancelled("semantic task cancelled");
                default -> TaskResult.fail("semantic task failed");
            };
        }
        if (interruptedChildResult != null && terminal != TaskState.SUCCESS) {
            result = withInterruptedEffects(result, interruptedChildResult);
        }
        Map<String, Object> projectData = new LinkedHashMap<>(result.data());
        addBuildProjects(projectData);
        result = new TaskResult(result.success(), result.message(), result.timedOut(), result.interrupted(), projectData);
        if (!terminalPublished) {
            terminalPublished = true;
            record.terminal(terminal, result, player.level().getGameTime());
            runtime.terminal(record, terminal, result);
        }
        return result;
    }

    private void retainInterruptedChild(TaskState state, TaskResult result) {
        interruptedChildResult = semanticResult(result == null ? TaskResult.fail(
                "Child returned no effect evidence during interruption.",
                Map.of("outcome_uncertain", true, "mechanical_retry_allowed", false)) : result);
        if (record.stepIndex() < record.steps().size()) {
            record.addAttempt(new IntentTaskRecord.AttemptSnapshot(record.stepIndex(), currentGoal(), state,
                    interruptedChildResult.message(), interruptedChildResult.toJson(), player.level().getGameTime()));
        }
    }

    /** Keep parent termination semantics and the child's exact partial-effect evidence together. */
    static TaskResult withInterruptedEffects(TaskResult parent, TaskResult child) {
        TaskResult clean = semanticResult(child);
        Map<String, Object> data = new LinkedHashMap<>(parent.data());
        data.put("interrupted_child", Map.of("message", clean.message(), "data", clean.data()));
        for (String key : List.of("outcome_uncertain", "effects_started", "mechanical_retry_allowed")) {
            if (clean.data().containsKey(key)) data.put(key, clean.data().get(key));
        }
        return new TaskResult(parent.success(), parent.message(), parent.timedOut(), parent.interrupted(), data);
    }

    private TaskResult successResult() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (IntentTaskRecord.StepSnapshot step : record.stepResults()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", step.index());
            item.put("ability", step.ability());
            item.put("success", step.success());
            item.put("message", step.message());
            steps.add(item);
        }
        return TaskResult.ok(record.goal().outcome(),
                Map.of("task_id", record.externalId().toString(), "steps", steps));
    }

    private static TaskResult parseImmediate(String json) {
        // 把立即回复的 JSON 换成统一结果；不合法的回复按工具失败处理，附加 data 暂存为 tool_data。
        try {
            JsonObject value = JsonParser.parseString(json).getAsJsonObject();
            boolean success = value.has("success") && value.get("success").getAsBoolean();
            String message = value.has("message") ? value.get("message").getAsString() : "";
            Map<String, Object> data = new LinkedHashMap<>();
            if (value.has("data") && value.get("data").isJsonObject()) {
                data.put("tool_data", value.getAsJsonObject("data").toString());
            }
            return success ? TaskResult.ok(message, data) : TaskResult.fail(message, data);
        } catch (RuntimeException exception) {
            return TaskResult.fail("internal tool returned invalid JSON: " + safeMessage(exception));
        }
    }

    private static TaskResult defaultResult(TaskState state) {
        return switch (state) {
            case SUCCESS -> TaskResult.ok("child completed");
            case TIMEOUT -> TaskResult.timeout("child timed out");
            case CANCELLED -> TaskResult.cancelled("child cancelled");
            default -> TaskResult.fail("child failed");
        };
    }

    private void clearChild() {
        child = null;
        childRecord = null;
        reobserveAfterChild=false;
    }

    /**
     * Apply only receipts whose human label is explicitly present in the current goal's
     * protected_labels.  Observing an area never silently turns it into a constraint.
     */
    private <T> T withExplicitAreaProtection(Supplier<T> operation) {
        refreshProtectionCache();
        return NavigationSafetyContext.withProtectedArea(
                cachedProtectedMutationCells, cachedForbiddenBodyCells, operation);
    }

    private void refreshProtectionCache() {
        // 只有当前目标明确点名要保护的区域才生效；把那些区域里不能挖、不能放、不能走的格子交给导航。
        String dimension = player.level().dimension().location().toString();
        int step = record.stepIndex();
        if (step == cachedProtectionStep && dimension.equals(cachedProtectionDimension)) return;
        cachedProtectionDimension = dimension;
        cachedProtectionStep = step;
        LongOpenHashSet mutation = new LongOpenHashSet();
        LongOpenHashSet body = new LongOpenHashSet();
        java.util.LinkedHashSet<String> requested = new java.util.LinkedHashSet<>(
                explicitProtectedLabels(currentGoal()));
        if ("maicraft:sequence".equals(record.goal().ability())) {
            requested.addAll(explicitProtectedLabels(record.goal()));
        }
        if (!requested.isEmpty()) {
            for (Map.Entry<Integer, List<InternalAreaProtectionReceipt.Footprint>> entry
                    : record.internalAreaProtectionReceipts().entrySet()) {
                if (entry.getKey() >= step) continue;
                for (InternalAreaProtectionReceipt.Footprint footprint : entry.getValue()) {
                    if (!dimension.equals(footprint.dimension())
                            || footprint.semanticLabel() == null
                            || !requested.contains(normalizeProtectionLabel(
                                    footprint.semanticLabel()))) continue;
                    footprint.protectedMutationCells().forEach(mutation::add);
                    footprint.forbiddenBodyCells().forEach(body::add);
                }
            }
        }
        cachedProtectedMutationCells = mutation.isEmpty()
                ? LongSets.emptySet() : LongSets.unmodifiable(mutation);
        cachedForbiddenBodyCells = body.isEmpty()
                ? LongSets.emptySet() : LongSets.unmodifiable(body);
    }

    private static Set<String> explicitProtectedLabels(Goal goal) {
        if (goal == null) return Set.of();
        return goal.protectionLabels().stream().map(IntentTask::normalizeProtectionLabel)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeProtectionLabel(String label) {
        return label.strip().toLowerCase(Locale.ROOT);
    }

    private void invalidateProtectionCache() {
        cachedProtectionStep = -1;
        cachedProtectionDimension = null;
        cachedProtectedMutationCells = LongSets.emptySet();
        cachedForbiddenBodyCells = LongSets.emptySet();
    }

    private void abandonChildAfterUnexpectedFailure() {
        Task failed = child;
        TaskRecord failedRecord = childRecord;
        if (failed == null) {
            releaseBody();
            return;
        }
        try {
            try {
                failed.stop(player, StopReason.REPLACED);
            } catch (RuntimeException ignoredFailure) {
                // result() below still gets a chance to release logical resources.
            }
            TaskState cleanupState = TaskState.FAILED;
            if (failedRecord != null) {
                if (!failedRecord.getState().isTerminal()) failedRecord.setState(cleanupState);
                cleanupState = failedRecord.getState();
            }
            try {
                TaskResult cleanupResult = failed.result(cleanupState);
                if (failedRecord != null && failedRecord.getResult() == null) {
                    failedRecord.setResult(cleanupResult);
                }
            } catch (RuntimeException ignoredFailure) {
                // Recovery must remain available even when child cleanup itself is broken.
            }
        } finally {
            releaseBody();
            if (child == failed) clearChild();
        }
    }

    /** Terminal body release is idempotent and must survive either half failing. */
    private void releaseBody() {
        try {
            InputDriver.halt(player);
        } catch (RuntimeException ignoredFailure) {
        }
        try {
            ClientRuntime.requireContext(player).body().releaseAll();
        } catch (RuntimeException ignoredFailure) {
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    /**
     * Internal tasks may use runtime entity ids, menu slots and concrete routes to keep native
     * work stable across ticks. Those implementation details stop here: the public semantic task
     * reports outcomes and evidence, never handles that the model could replay as micro-actions.
     */
    private static TaskResult semanticResult(TaskResult raw) {
        if (raw == null) return TaskResult.fail("internal action failed");
        return new TaskResult(
                raw.success(),
                sanitizeMessage(raw.message()),
                raw.timedOut(),
                raw.interrupted(),
                sanitizeMap(raw.data()));
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source) {
        // 对外回复前，按字段名删掉内部使用的坐标、路径、槽位等；嵌套的 Map、列表和 JSON 也继续检查。
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || internalResultKey(key)) continue;
            Object value = sanitizeEntry(key, entry.getValue());
            if (value != null) clean.put(key, value);
        }
        return Map.copyOf(clean);
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof com.google.gson.JsonElement json) {
            return sanitizeJson(json);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (internalResultKey(key)) continue;
                Object nested = sanitizeEntry(key, entry.getValue());
                if (nested != null) clean.put(key, nested);
            }
            return Map.copyOf(clean);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> clean = new ArrayList<>();
            for (Object element : collection) {
                Object nested = sanitizeValue(element);
                if (nested != null) clean.add(nested);
            }
            return List.copyOf(clean);
        }
        if (value instanceof String text) {
            String stripped = text.strip();
            if ((stripped.startsWith("{") && stripped.endsWith("}"))
                    || (stripped.startsWith("[") && stripped.endsWith("]"))) {
                try {
                    return sanitizeJson(JsonParser.parseString(stripped));
                } catch (RuntimeException ignored) {
                    // Ordinary text that merely resembles JSON remains ordinary text.
                }
            }
            return sanitizeMessage(text);
        }
        return value;
    }

    private static Object sanitizeJson(com.google.gson.JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : value.getAsJsonObject().entrySet()) {
                if (internalResultKey(entry.getKey())) continue;
                Object nested = sanitizeEntry(entry.getKey(), entry.getValue());
                if (nested != null) clean.put(entry.getKey(), nested);
            }
            return Map.copyOf(clean);
        }
        if (value.isJsonArray()) {
            List<Object> clean = new ArrayList<>();
            for (com.google.gson.JsonElement element : value.getAsJsonArray()) {
                Object nested = sanitizeJson(element);
                if (nested != null) clean.add(nested);
            }
            return List.copyOf(clean);
        }
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        return sanitizeMessage(primitive.getAsString());
    }

    private static Object sanitizeEntry(String key, Object value) {
        // 已经实际挖过的方块是供人核查的事实，因此这个字段例外保留位置，最多列出三十二块。
        if (!"confirmed_harvests".equals(key)) return sanitizeValue(value);
        // Committed block changes are auditable world evidence, not a replayable planned route.
        var json = new com.google.gson.Gson().toJsonTree(value);
        if (!json.isJsonArray()) return List.of();
        List<Object> result = new ArrayList<>();
        for (var entry : json.getAsJsonArray()) {
            if (result.size() == 32) break;
            if (!entry.isJsonObject()) continue;
            var row = entry.getAsJsonObject();
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String field : List.of("block_id", "block_state", "natural_tree_filter_enabled")) {
                if (row.has(field)) clean.put(field, sanitizeJson(row.get(field)));
            }
            if (row.has("position") && row.get("position").isJsonObject()) {
                var pos = row.getAsJsonObject("position");
                Map<String, Integer> coordinates = new LinkedHashMap<>();
                for (String axis : List.of("x", "y", "z")) {
                    var number = pos.get(axis);
                    if (number != null && number.isJsonPrimitive() && number.getAsJsonPrimitive().isNumber()
                            && number.getAsDouble() == number.getAsInt()) coordinates.put(axis, number.getAsInt());
                }
                if (coordinates.size() == 3) clean.put("position", Map.copyOf(coordinates));
            }
            result.add(clean);
        }
        return List.copyOf(result);
    }

    private static boolean internalResultKey(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        return INTERNAL_RESULT_KEYS.contains(key)
                || key.endsWith("_cells")
                || key.endsWith("_ops")
                || key.endsWith("_placements")
                || key.endsWith("_receipts")
                || key.endsWith("_routes")
                || key.endsWith("_waypoints")
                || key.endsWith("_path_nodes")
                || key.endsWith("_position")
                || key.endsWith("_center")
                || key.endsWith("_location")
                || key.endsWith("_destination")
                || key.endsWith("_bounds")
                || key.endsWith("_x")
                || key.endsWith("_y")
                || key.endsWith("_z")
                || key.endsWith("_entity_id")
                || key.endsWith("_entity_ids")
                || key.endsWith("_runtime_id")
                || key.endsWith("_runtime_ids");
    }

    private static String sanitizeMessage(String raw) {
        if (raw == null) return "";
        return raw
                // 教学消息的既定句式先整句归形,再做通用坐标清洗——否则
                // "reached the exact cell -399,65,331." 会被洗成
                // "reached the exact cell the internally verified location." 这样的病句
                // (MoveTo 的成功文案 + 坐标隐私清洗叠加的实锅)。
                .replaceFirst(
                        "(?i)reached the exact cell -?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\.",
                        "reached the exact target cell.")
                .replaceFirst(
                        "(?i)arrived at location x\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\s+z\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?,"
                                + "\\s*standing on the ground at y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\.",
                        "arrived at the target location, standing on solid ground.")
                .replaceFirst(
                        "(?i)The exact cell y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)? wasn't reachable",
                        "The exact requested cell wasn't reachable")
                .replaceAll("(?i)entity\\s*#?\\s*\\d+", "selected entity")
                .replaceAll("(?i)runtime\\s+id\\s*[:=]?\\s*\\d+", "internal target")
                .replaceAll(
                        "(?i)(?:location\\s+)?x\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?\\s+z\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?(?:,?\\s*standing\\s+on\\s+the\\s+ground\\s+at\\s+y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?)?",
                        "the internally verified location")
                .replaceAll("(?<!\\d)-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+(?!\\d)",
                        "the internally verified location")
                .replaceAll("(?i)\\b[xyz]\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?",
                        "the internally verified coordinate");
    }

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public Map<String, Object> progress() {
        Map<String, Object> progress = new LinkedHashMap<>(child != null ? sanitizeMap(child.progress())
                : Map.of("phase", record.decisionSnapshot() != null ? "waiting_for_decision"
                : wait != null ? "waiting_for_condition" : "preparing_step"));
        addBuildProjects(progress);
        return Map.copyOf(progress);
    }

    private void addBuildProjects(Map<String, Object> data) {
        List<String> ids = record.steps().stream().filter(step -> "maicraft:build".equals(step.ability()))
                .map(Goal::parameters).filter(parameters -> parameters.has("project_id"))
                .map(parameters -> parameters.get("project_id").getAsString()).distinct().toList();
        if (ids.size() == 1) data.put("project_id", ids.getFirst());
        if (!ids.isEmpty()) data.put("build_projects", ids);
    }
}
