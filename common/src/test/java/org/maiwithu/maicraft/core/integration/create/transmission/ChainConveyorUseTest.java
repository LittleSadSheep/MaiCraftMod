// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create.transmission;

import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskResult;
import org.maiwithu.maicraft.core.integration.create.transmission.ChainConveyorUse.Action;

/** 原生选点不扣锁链；接线必须同时对上双向连接和材料账，缺料必须直接送到模型的完成通知。 */
public final class ChainConveyorUseTest {
    public static void main(String[] args) throws Exception {
        check(verdict(Action.SELECT_FIRST,true,false,true,false,2,2,0)==Verdict.APPLIED,"selection alone confirms first click");
        check(verdict(Action.SELECT_FIRST,false,false,true,false,2,2,0)==Verdict.PENDING,"unchanged appearance is not selection proof");
        check(verdict(Action.SELECT_FIRST,true,false,true,false,2,1,0)==Verdict.DIVERGED,"selection must not consume a chain");
        check(verdict(Action.CLEAR_SELECTION,false,true,true,false,2,2,0)==Verdict.APPLIED,"explicit clear confirms without a link");
        check(verdict(Action.CONNECT,false,true,true,false,12,12,8)==Verdict.PENDING,"cleared marker is not a connection");
        check(verdict(Action.CONNECT,false,true,false,true,12,12,8)==Verdict.PENDING,"reciprocal connection still waits for exact inventory debit");
        check(verdict(Action.CONNECT,false,false,false,true,12,4,8)==Verdict.PENDING,"linked endpoint still waits for selection to clear");
        check(verdict(Action.CONNECT,false,true,false,true,12,4,8)==Verdict.APPLIED,"reciprocal link and exact debit confirm native success");
        check(verdict(Action.CONNECT,false,true,false,true,12,3,8)==Verdict.DIVERGED,"unrelated consumption cannot prove this connection");
        check(verdict(Action.CONNECT,false,true,false,true,1,1,0)==Verdict.APPLIED,"creative linking confirms without consuming its held chain");
        check(ChainConveyorUse.materialFailure(2,8,false).contains("required=8, available=2, missing=6"),"material failure names the actionable shortage");
        check(ChainConveyorUse.materialFailure(8,8,false)==null && ChainConveyorUse.materialFailure(1,8,true)==null,"sufficient or creative materials remain admissible");
        // 普通语义结果和注意流都要保留数量；不能再次只留下一个 UNKNOWN 或超时摘要。
        var report=TaskResult.fail("chain_conveyor_insufficient_chains",Map.of("chain_conveyor_use",Map.of(
                "action","connect","chains_required",8,"chains_available_before",2,"chains_missing",6,"chain_link_verified",false)));
        var compact=IntentRuntime.class.getDeclaredMethod("compactAttentionResult",JsonObject.class); compact.setAccessible(true);
        var notice=(JsonObject)compact.invoke(null,JsonParser.parseString(SemanticResultView.result(report).toJson()).getAsJsonObject());
        var data=notice.getAsJsonObject("data").getAsJsonObject("chain_conveyor_use");
        check(data.get("chains_missing").getAsInt()==6 && !data.get("chain_link_verified").getAsBoolean(),"attention retains shortage and incomplete connection");
        System.out.println("ChainConveyorUseTest: passed");
    }
    private static Verdict verdict(Action action, boolean selected, boolean cleared, boolean unchanged,
            boolean linked, int before, int after, int cost) {
        return ChainConveyorUse.verdict(action,selected,cleared,unchanged,linked,before,after,cost);
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
