// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.mcp.knowledge.PonderKnowledgeSource;

public final class PonderKnowledgeTest {
    public static void main(String[] args) throws Exception {
        var access = PonderFixture.access(); PonderFixture.compiled = 0;
        var snapshot = access.snapshot();
        check(snapshot.status().equals("available") && snapshot.entries().size() == 2, "discover arbitrary addon scenes");
        check(!snapshot.entries().get(0).key().equals(snapshot.entries().get(1).key()), "duplicate schematic registrations need separate URIs");
        var source = new PonderKnowledgeSource(access, id -> "插件机器");
        source.entries(); source.read(PonderKnowledgeSource.componentUri("addon:machine"));
        check(PonderFixture.compiled == 0, "index/component discovery must not compile all scenes");
        var doc = source.read(PonderKnowledgeSource.SCENE + snapshot.entries().getFirst().key());
        check(PonderFixture.compiled == 1, "read compiles one exact storyboard");
        check(doc.text().contains("A source rule") && doc.text().contains("Shared source text"), "local and shared fallback narration");
        check(doc.text().contains("潜行 + 右键") && doc.text().contains("(5.0, 6.0, 7.0)"), "control and focus extraction");
        check(doc.text().contains("WorldModifyInstruction") && PonderFixture.GLOBAL.specific.isEmpty(), "opaque code is not executed and globals stay unchanged");
        check(source.read("maicraft://knowledge/ponder/scene/unknown") == null, "unknown URI must not guess a scene");
        var absent = new ReflectivePonderAccess(new ClassLoader(null) {}).snapshot();
        check(absent.status().equals("not_installed"), "optional Ponder absence is explicit");
        PonderFixture.brokenEntry = true;
        var partial = access.snapshot();
        check(partial.status().equals("partial") && partial.entries().size() == 2, "one broken addon entry must not erase other tutorials");
        PonderFixture.brokenEntry = false;
        List<PonderTranscript.Step> steps = new ArrayList<>();
        for (int i = 0; i < 41; i++) steps.add(new PonderTranscript.Step(i, i, "旁白", "line-" + i, null));
        var transcript = new PonderTranscript("x:y", "Title", steps, Map.of(), List.of());
        String first = transcript.markdown(snapshot.entries().getFirst(), "maicraft://knowledge/ponder/scene/example", 0);
        String last = transcript.markdown(snapshot.entries().getFirst(), "maicraft://knowledge/ponder/scene/example", 40);
        check(first.contains("?offset=40") && !first.contains("line-40"), "progressive scene page");
        check(last.contains("line-40") && !last.contains("line-0"), "next page retains omitted content");
        System.out.println("PonderKnowledgeTest: passed");
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
