// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.core.integration.ponder.PonderAccess;
import org.maiwithu.maicraft.core.integration.ponder.PonderTranscript;

/** 机器知识依附冻结的观察；缺失部件、未知现场和不同目标身份不能被提示改写成运行结论。 */
public final class MachineKnowledgeTest {
    public static void main(String[] args) {
        JsonObject report = JsonParser.parseString("""
                {"snapshot_id":"same-observation","palette":[{"block_id":"minecraft:hopper"}],
                 "operating_state":{"production_verified":false,"other_kinetic_components":[]},
                 "as_built_blueprint":{"blocks":[{"block_id":"minecraft:hopper","offset":[0,0,0]}]},
                 "blueprint_diff":{"structure_matches_blueprint":false,"differences":[
                   {"expected":{"block_id":"minecraft:furnace"},"actual":{"block_id":"minecraft:hopper"}},
                   {"status":"unknown","expected":{"block_id":"minecraft:chest"},"actual":null}]}}
                """).getAsJsonObject();
        var original = report.deepCopy(); MachineKnowledge.attach(report);
        var knowledge = report.getAsJsonObject("component_knowledge");
        check(knowledge.getAsJsonArray("block_resources").size() == 3, "all observed and expected block types have direct resources");
        var hopper = knowledge.getAsJsonArray("related_resources").get(0).getAsJsonObject();
        check(hopper.getAsJsonArray("roles").toString().equals("[\"observed\",\"actual\"]"), "actual roles retain native identity");
        var chest = knowledge.getAsJsonArray("related_resources").get(2).getAsJsonObject();
        check(chest.getAsJsonArray("roles").toString().equals("[\"expected\"]"), "unloaded expected block never becomes an observed block");
        check(!knowledge.getAsJsonArray("pitfall_notes").isEmpty(), "known hopper transfer rule accompanies the observation");
        report.remove("component_knowledge"); check(report.equals(original), "knowledge does not change captured states, diff or snapshot identity");
        // 知识提供者失败时，原生观察依然可交付，不能让只读知识异常把动作或观察改判失败。
        var broken = new ItemKnowledge(new PonderAccess() {
            @Override public Snapshot snapshot() { throw new IllegalStateException("unavailable"); }
            @Override public PonderTranscript compile(Entry entry) { throw new AssertionError("must not compile"); }
        });
        MachineKnowledge.attach(report, broken);
        check(report.getAsJsonObject("component_knowledge").get("status").getAsString().equals("lookup_unavailable"), "lookup failure remains a knowledge status");
        report.remove("component_knowledge"); check(report.equals(original), "provider failure leaves native facts intact");
        System.out.println("MachineKnowledgeTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
