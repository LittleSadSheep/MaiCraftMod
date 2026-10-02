// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.maiwithu.maicraft.task.TaskResult;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestActionAccess.Prepared;

/** 准备 -> 持久预约 -> 单次提交 -> 等待同步并结算；发出请求与观察到奖励/任务变化分别报告。 */
public final class FtbQuestActionSession {
    public enum State { RUNNING, SUCCESS, FAILED, CANCELLED }
    private final FtbQuestActionRequest request;
    private final FtbQuestActionAccess access;
    private final BooleanSupplier barrier;
    private final LongSupplier clock;
    private Prepared prepared;
    private JsonObject after;
    private State state = State.RUNNING;
    private boolean reserved, attempted, sent, uncertain;
    private boolean ftbUpdateObserved;
    private final JsonArray updates = new JsonArray();
    private long sentAt, settleAt = Long.MAX_VALUE;
    private String status = "not_submitted", detail = "等待任务书原生操作";
    private String observationProblem;

    public FtbQuestActionSession(FtbQuestActionRequest request, FtbQuestActionAccess access, BooleanSupplier barrier, LongSupplier clock) {
        this.request = request; this.access = access; this.barrier = barrier; this.clock = clock;
    }
    public State tick() {
        if (state != State.RUNNING) return state;
        try {
            if (!attempted) {
                if (prepared == null) prepared = access.prepare(request);
                if (prepared.alreadySatisfied()) return finish("already_satisfied", "FTB 已记录任务完成或奖励领取，本次未再次提交；历史物品到账不据此推断");
                reserved = true;
                if (!barrier.getAsBoolean()) return state;
                // 保存期间背包或进度可能变化；提交前重新观察，已经被玩家或队友完成的目标不再消费。
                Prepared current = access.prepare(request);
                if (!prepared.scope().equals(current.scope())) throw new IllegalStateException("FTB action context changed before submission");
                prepared = current;
                if (prepared.alreadySatisfied()) return finish("already_satisfied", "提交前 FTB 状态已满足，本次没有再次消费");
                attempted = true; sentAt = clock.getAsLong(); access.submit(prepared); sent = true;
                status = "submitted_to_client"; detail = "已发出一次 FTB 原生请求，等待同步事实";
            }
            JsonObject observed;
            try { observed = access.observe(prepared); }
            catch (RuntimeException | LinkageError unavailable) {
                observationProblem = unavailable.getMessage(); uncertain = true;
                return finish(status, "请求已发出，后续观察不可用；保留已有事实，不自动重发");
            }
            long now = clock.getAsLong();
            boolean ftbChanged = ftbChanged(prepared.before(), observed);
            if (ftbChanged && (after == null || !stable(after, observed))) settleAt = now + 250;
            retain(observed); ftbUpdateObserved |= ftbChanged;
            if (now >= settleAt || now - sentAt >= 3000) {
                uncertain = !ftbUpdateObserved;
                return finish(status, ftbUpdateObserved ? "原生请求已提交，已取得 FTB 状态与背包的后续观察"
                        : "原生请求已提交，观察期内 FTB 状态未变；这不能证明服务器接受或拒绝了请求");
            }
        } catch (RuntimeException | LinkageError failure) {
            // 预约冲突可能来自重启前的提交；异常和取消都不重新开放这一笔消费。
            uncertain = attempted || reserved; status = attempted ? "submission_uncertain" : reserved ? "reservation_unavailable" : "not_submitted";
            detail = failure.getMessage() == null ? "FTB 原生操作不可用" : failure.getMessage(); state = State.FAILED;
            observeOnce();
        }
        return state;
    }
    private static boolean stable(JsonObject previous, JsonObject current) {
        return previous.get("ftb").equals(current.get("ftb")) && previous.get("inventory").equals(current.get("inventory"));
    }
    private static boolean ftbChanged(JsonObject before, JsonObject after) {
        JsonObject a = before.getAsJsonObject("ftb"), b = after.getAsJsonObject("ftb");
        if (b.has("subject_available") && !b.get("subject_available").getAsBoolean()) return false;
        if (!Objects.equals(a.get("quest_completion_count"), b.get("quest_completion_count"))) return true;
        if (a.has("reward") && b.has("reward")) {
            a = a.getAsJsonObject("reward"); b = b.getAsJsonObject("reward");
            return !Objects.equals(a.get("claimed"), b.get("claimed")) || !Objects.equals(a.get("claimed_at"), b.get("claimed_at"));
        }
        return !Objects.equals(a.get("progress"), b.get("progress")) || !Objects.equals(a.get("task_completed"), b.get("task_completed"));
    }
    private void retain(JsonObject observed) {
        // 重复任务可能领奖后立即重置；保留真正观察过的中间变化，不能用最后一次零进度抹掉已确认效果。
        JsonObject previous = after == null ? prepared.before() : after, change = new JsonObject();
        for (String key : new String[]{"ftb", "inventory", "player"})
            if (!Objects.equals(previous.get(key), observed.get(key))) change.add(key, observed.get(key).deepCopy());
        if (!change.isEmpty()) { change.add("observed_at", observed.get("observed_at")); updates.add(change); }
        after = observed;
    }
    private State finish(String status, String detail) { this.status = status; this.detail = detail; state = State.SUCCESS; return state; }
    public boolean submitted() { return attempted; }
    public void fail(String reason) {
        if (state != State.RUNNING) return;
        observeOnce(); state = State.FAILED; detail = reason; uncertain |= attempted && !ftbUpdateObserved;
    }
    public void cancel(String reason) {
        if (state != State.RUNNING) return;
        observeOnce(); state = State.CANCELLED; detail = reason; uncertain |= attempted && !ftbUpdateObserved;
    }
    private void observeOnce() {
        if (prepared == null) return;
        try { JsonObject observed = access.observe(prepared); ftbUpdateObserved |= ftbChanged(prepared.before(), observed); retain(observed); }
        catch (RuntimeException | LinkageError unavailable) { observationProblem = unavailable.getMessage(); }
    }
    public Map<String, Object> evidence() {
        JsonObject receipt = new JsonObject(); receipt.add("request", request.json()); receipt.addProperty("submission_status", status);
        receipt.addProperty("submission_attempted", attempted); receipt.addProperty("submitted_to_client", sent);
        receipt.addProperty("ftb_update_observed", ftbUpdateObserved);
        receipt.addProperty("server_acknowledgement", "FTB native messages do not provide a request-specific acknowledgement");
        if (!updates.isEmpty()) receipt.add("observed_updates", updates.deepCopy());
        if (prepared != null) receipt.add("before", prepared.before().deepCopy());
        if (after != null) receipt.add("after", after.deepCopy());
        if (prepared != null && after != null) receipt.add("inventory_changes", FtbInventoryEvidence.difference(
                prepared.before().getAsJsonObject("inventory"), after.getAsJsonObject("inventory")));
        if (observationProblem != null) receipt.addProperty("observation_problem", observationProblem);
        receipt.addProperty("attribution", "同步进度可能包含队友贡献，背包变化也可能包含同期游戏效果；领取记录和实际到账分别判断");
        var result = new LinkedHashMap<String, Object>(); result.put("quest_action", receipt);
        result.put("outcome_uncertain", uncertain); result.put("mechanical_retry_allowed", !attempted && !reserved && state != State.CANCELLED);
        return result;
    }
    public TaskResult result() { return new TaskResult(state == State.SUCCESS, detail, false, state == State.CANCELLED, evidence()); }
}
