package org.maiwithu.maicraft.core.task.build;

public final class BuildSiteConstraintsTest {
    // 地下室撞到基岩或高度边界就拒绝开工；已经匹配的基岩地板则按原样保留。
    public static void main(String[] args) {
        check("unbreakable_terrain".equals(BuildSiteConstraints.conflict(false, false, true, -1)),
                "a basement must not excavate bedrock at any world elevation");
        check("unbreakable_terrain".equals(BuildSiteConstraints.conflict(false, false, true, -2)),
                "negative modded hardness is also unbreakable");
        check(BuildSiteConstraints.conflict(true, false, true, -1) == null,
                "an already matching retained unbreakable floor needs no excavation");
        check("outside_build_height".equals(BuildSiteConstraints.conflict(true, true, true, 0)),
                "out-of-world air cannot stand in for a valid underground room");
        check("outside_world_border".equals(BuildSiteConstraints.conflict(true, false, false, 0)),
                "matching air outside the border must not pass site validation");
        check(BuildSiteConstraints.conflict(false, false, true, 100) == null,
                "hard but breakable natural terrain remains feasible");
        System.out.println("BuildSiteConstraintsTest: passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
