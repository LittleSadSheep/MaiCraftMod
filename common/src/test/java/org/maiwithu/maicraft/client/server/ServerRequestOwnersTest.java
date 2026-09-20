// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.server;

import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import com.google.gson.JsonObject;
import static org.maiwithu.maicraft.client.server.ServerRouterTestHarness.check;

public final class ServerRequestOwnersTest {
    public static void main(String[] args) {
        var h = new ServerRouterTestHarness(true);
        h.welcome("test.write");
        TaskRecord owner = new TaskRecord("test", "test-owner", 2) {
            @Override public String describe() { return "bounded owner"; }
        };
        owner.setState(TaskState.RUNNING);
        var owners = new ServerRequestOwners();
        var queued = h.submit("test.write");
        owners.remember(queued.id(), owner.publicId());
        owners.retire(h.router, id -> owner, 2);
        h.advance(2);
        check(h.count("request") == 0 && queued.snapshot().status() == ClientRequestReceipt.Status.CANCELLED,
                "owner deadline is checked before the scheduler and before a queued remote mutation is sent");
        owner.extendDeadlineTo(10);
        var sent = h.submit("test.write");
        owners.remember(sent.id(), owner.publicId());
        h.advance(3);
        owner.setState(TaskState.CANCELLED);
        owners.retire(h.router, id -> owner, 4);
        h.advance(4);
        check(h.count("request") == 1 && h.count("cancel") == 1 && sent.snapshot().unresolvedMutation(),
                "terminal owner retires submitted work without asserting rollback or replaying it");
        selectedOwnerRunsAfterReflexSelection();
        cleanupReadSurvivesTerminalOwner();
        System.out.println("ServerRequestOwnersTest: passed");
    }

    private static void cleanupReadSurvivesTerminalOwner() {
        var h = new ServerRouterTestHarness(true); h.welcome("test.read");
        var owners = new ServerRequestOwners();
        TaskRecord owner = new TaskRecord("test", "finished-process", 10) {
            @Override public String describe() { return "ended production watcher"; }
        };
        owner.setState(TaskState.CANCELLED);
        var owned = h.submit("test.read"); owners.remember(owned.id(), owner.publicId());
        var body = new JsonObject(); body.addProperty("release_watch", true);
        var cleanup = h.router.submit("test.read", body, false); owners.remember(cleanup.id(), null);
        // 任务结束会回收其普通请求；只读观察释放显式无owner，下一刻仍可发出，不会操作机器或材料。
        owners.retire(h.router, id -> owner, 2); h.advance(2);
        check(owned.snapshot().status() == ClientRequestReceipt.Status.CANCELLED
                        && cleanup.snapshot().status() != ClientRequestReceipt.Status.CANCELLED
                        && h.count("request") == 1 && h.last("request").getAsJsonObject("body").get("release_watch").getAsBoolean(),
                "an ownerless observation release survives the finished task's retirement and is dispatched exactly once");
    }

    private static void selectedOwnerRunsAfterReflexSelection() {
        var h = new ServerRouterTestHarness(false);
        var owners = new ServerRequestOwners();
        var background = h.submit("test.write");
        var synchronous = h.submit("test.other");
        owners.remember(background.id(), "background");
        owners.remember(synchronous.id(), "synchronous");
        h.router.observe(1);
        h.router.dispatch(ServerRequestOwners.ordinaryWinner("emergency_rescue"), receipt -> true);
        check(h.local.submissions == 0, "the selected emergency runs before any queued ordinary remote mutation");
        h.router.dispatch(true, receipt -> owners.selected(receipt.id(), "synchronous"));
        h.drain();
        check(h.local.submissions == 1 && background.snapshot().status() == ClientRequestReceipt.Status.QUEUED,
                "only the currently selected slot may dispatch its pending mutation");
        h.router.observe(2);
        h.router.dispatch(true, receipt -> owners.selected(receipt.id(), "background"));
        h.drain();
        check(h.local.submissions == 2 && background.snapshot().status() == ClientRequestReceipt.Status.SUCCEEDED,
                "preempted unsent work waits until its owner wins a later tick");
    }
}
