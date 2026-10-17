// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SignLines;
import org.maiwithu.maicraft.behavior.travel.TravelFakes.FakePlaces;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 地标解析：记住的地点优先，没记过认唯一写着这个名字的告示牌，几块同名挨着算一处、隔开不挑。 */
class LandmarkResolverTest {

    private final FakePlaces places = new FakePlaces();
    private final List<SceneSign> signs = new ArrayList<>();

    private void sign(String id, WorldPosition at, int distance, String compass, SignLines lines) {
        signs.add(new SceneSign(id, "minecraft:oak_sign", at, "前方", compass, distance, lines));
    }

    private void sign(String id, WorldPosition at, int distance, String... frontLines) {
        sign(id, at, distance, "东", new SignLines(List.of(frontLines), List.of()));
    }

    private LandmarkResolver.Resolution resolve(String name) {
        return LandmarkResolver.resolve(name, places, () -> signs);
    }

    private WorldPosition found(String name) {
        return assertInstanceOf(LandmarkResolver.Resolution.Found.class, resolve(name)).position();
    }

    private LandmarkResolver.Resolution.Missing missing(String name) {
        return assertInstanceOf(LandmarkResolver.Resolution.Missing.class, resolve(name));
    }

    @Test
    void rememberedPlaceWinsOverASignWithTheSameName() {
        places.put("仓库", new WorldPosition(300, 70, 300, null));
        sign("b1", WorldPosition.here(5, 64, 0), 5, "仓库");
        assertEquals(new WorldPosition(300, 70, 300, null), found("仓库"));
    }

    @Test
    void theOnlySignReadingTheNameIsThePlace() {
        sign("b1", WorldPosition.here(5, 64, 0), 5, "【仓库】");
        sign("b2", WorldPosition.here(-8, 64, 2), 8, "张三的仓库");
        assertEquals(WorldPosition.here(5, 64, 0), found("仓库"), "只有写着「仓库」的那块算，「张三的仓库」不算");
    }

    @Test
    void aNameSplitOverTwoLinesIsRecognised() {
        sign("b1", WorldPosition.here(5, 64, 0), 5, "仓", "库");
        assertEquals(WorldPosition.here(5, 64, 0), found("仓库"));
    }

    @Test
    void signsRightNextToEachOtherAreOnePlace() {
        // 门两边各钉一块"仓库"：那是同一处，去最近那块，不用问。
        sign("b1", WorldPosition.here(10, 64, 0), 10, "仓库");
        sign("b2", WorldPosition.here(8, 64, 0), 8, "仓库");
        assertEquals(WorldPosition.here(8, 64, 0), found("仓库"));
    }

    @Test
    void signsFarApartAreNotPickedFor() {
        // 两处基地各有一块"仓库"：不挑，列出两块的编号、方位、距离让 LLM 用 seen 指。
        sign("b3", WorldPosition.here(9, 64, -9), 12, "东北", new SignLines(List.of("仓库"), List.of()));
        sign("b7", WorldPosition.here(-30, 64, 0), 30, "西", new SignLines(List.of("仓库"), List.of()));
        var missing = missing("仓库");
        assertTrue(missing.reason().contains("2 块告示牌都写着「仓库」"), missing.reason());
        assertTrue(missing.reason().contains("b3（东北 12 格）、b7（西 30 格）"), missing.reason());
        assertTrue(missing.suggestionOr("先记下").contains("seen"), "建议用观察编号指一块");
    }

    @Test
    void noSignReadingTheNameSaysSoAndLeavesTheAbilitysOwnSuggestion() {
        sign("b1", WorldPosition.here(5, 64, 0), 5, "矿洞");
        var missing = missing("仓库");
        assertTrue(missing.reason().contains("没记住叫「仓库」的地方"), missing.reason());
        assertTrue(missing.reason().contains("告示牌上也没有写着「仓库」"), missing.reason());
        assertNull(missing.suggestion());
        assertEquals("先记下", missing.suggestionOr("先记下"));
    }

    @Test
    void aSignWhoseTextHasNotArrivedReadsNothing() {
        // 客户端还没收到牌子上的字：不知道写了什么，不当成写着这个名字。
        sign("b1", WorldPosition.here(5, 64, 0), 5, "东", null);
        missing("仓库");
    }

    @Test
    void signsAreOnlyReadWhenThePlaceIsNotRemembered() {
        places.put("家", new WorldPosition(1, 2, 3, null));
        var resolution = LandmarkResolver.resolve("家", places, () -> {
            throw new AssertionError("记过的地点不用看牌子");
        });
        assertInstanceOf(LandmarkResolver.Resolution.Found.class, resolution);
    }
}
