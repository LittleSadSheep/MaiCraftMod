package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;

/** Sign labels are readable data, including Chinese, both sides and client-filtered text. */
public final class NearbySignPerceptionTest {
    public static void main(String[] args) {
        SignText front = new SignText().setMessage(0, Component.literal("喷气背包"))
                .setMessage(1, Component.literal("充气点"))
                .setMessage(2, Component.literal("动力轴下方"));
        SignText back = new SignText().setMessage(0, Component.literal("JETPACK FILL"));
        var frontLines = NearbySignPerception.lines(front, false);
        var backLines = NearbySignPerception.lines(back, false);
        check(frontLines.size() == 4 && frontLines.get(1).getAsString().equals("充气点"),
                "Chinese sign text and line ordering must survive perception");
        check(NearbySignPerception.matches(frontLines, backLines, "充气"), "search must match the front");
        check(NearbySignPerception.matches(frontLines, backLines, "jetpack"), "search must match the back case-insensitively");
        check(!NearbySignPerception.matches(frontLines, backLines, "睡眠"), "unrelated signs must not match");
        SignText filtered = new SignText().setMessage(0, Component.literal("original"), Component.literal("filtered"));
        check(NearbySignPerception.lines(filtered, true).get(0).getAsString().equals("filtered"),
                "honor the Minecraft client's text-filtering selection");
        String longText = "充".repeat(255) + "😀".repeat(10);
        String clipped = NearbySignPerception.lines(new SignText().setMessage(0, Component.literal(longText)), false)
                .get(0).getAsString();
        check(clipped.codePointCount(0, clipped.length()) == 256 && clipped.endsWith("😀"),
                "bounded text must not split a surrogate pair");
        BlockPos upstairs = new BlockPos(-83, 115, -13);
        check(NearbySignPerception.inside(upstairs, new BlockPos(-86, 103, -12), 32),
                "a label downstairs must not be excluded by a same-floor scan");
        check(!NearbySignPerception.inside(upstairs, new BlockPos(-83, 80, -13), 32),
                "sign observation must retain its declared vertical bound");
        JsonObject request = new JsonObject();
        request.addProperty("view", "surroundings"); request.addProperty("focus", "充气");
        check(PublicToolCatalog.validateAndNormalize("maicraft_perceive", request).get("focus").getAsString().equals("充气"),
                "the public tool contract must accept a literal Chinese sign query");
        request.addProperty("view", "abilities");
        try {
            PublicToolCatalog.validateAndNormalize("maicraft_perceive", request);
            throw new AssertionError("ability filters must still use resource IDs");
        } catch (IllegalArgumentException expected) { }
        System.out.println("NearbySignPerceptionTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
