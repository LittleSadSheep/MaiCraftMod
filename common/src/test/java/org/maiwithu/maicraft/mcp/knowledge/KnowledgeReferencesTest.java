// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.SemanticAbilityCatalog;

/** 选定能力后给出的资料必须确实可读，读取教材不需要玩家先开菜单或移动到工地。 */
public final class KnowledgeReferencesTest {
    public static void main(String[] args) {
        var library = KnowledgeLibrary.offline();
        int linked = 0;
        for (String ability : IntentRuntime.KNOWN_ABILITIES) {
            JsonObject contract = SemanticAbilityCatalog.describe(ability);
            if (!contract.has("related_knowledge")) continue;
            linked++;
            for (var value : contract.getAsJsonArray("related_knowledge")) {
                var reference = value.getAsJsonObject();
                var arguments = reference.getAsJsonObject("read_arguments");
                var request = KnowledgeLibrary.perceptionRequest(arguments);
                var contents = library.request(request).getAsJsonArray("contents");
                check(contents.size() == 1 && !contents.get(0).getAsJsonObject().get("text").getAsString().isBlank(),
                        "ability knowledge must resolve with its exact read_arguments: " + ability);
            }
        }
        check(linked >= 10, "building, machines and material abilities expose their relevant references");
        check(!SemanticAbilityCatalog.describe("maicraft:chat").has("related_knowledge"), "unrelated actions do not carry broad reading checklists");
        var read = KnowledgeReferences.ability("maicraft:inspect_machine", "读取观察契约").getAsJsonObject("read_arguments");
        check(read.get("focus").getAsString().equals("maicraft:inspect_machine"), "ability hints select an existing exact contract");
        System.out.println("KnowledgeReferencesTest: passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
