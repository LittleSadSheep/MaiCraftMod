// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.List;

/**
 * 检查机器类别只作为线索、只把共面的格子算相邻、输出被截断时仍保留原编号和遗漏数；勘察半径限制与施工规划预算分别处理。
 */
public final class MachineSurveyModelTest {
    public static void main(String[] args) {
        classifyOptionalModFamiliesWithoutPretendingConnectivity();
        preserveBlockIndicesAndRejectDiagonals();
        reportDroppedEdgesWithoutChangingRetainedEdges();
        boundVolumeAgainstUntrustedRadius();
        System.out.println("MachineSurveyModelTest: passed");
    }

    private static void classifyOptionalModFamiliesWithoutPretendingConnectivity() {
        check(MachineSurveyModel.classify("create:shaft").roles().equals(List.of("possible_rotational_transmission")),
                "Create shafts should be hints, not proof of power");
        check(MachineSurveyModel.classify("ae2:cable_bus").roles().equals(List.of("possible_multipart_network_host")),
                "AE2 cable hosts cannot stand in for their unobserved parts");
        check(MachineSurveyModel.classify("mekanism:ultimate_pressurized_tube").roles().contains("possible_chemical_transport"),
                "Mekanism chemicals must not be presented as fluids or items");
        check(MachineSurveyModel.classify("mekanismgenerators:wind_generator").family().equals("mekanism"),
                "Mekanism generators belong in the same family");
        check(MachineSurveyModel.classify("minecraft:hopper").relevant(), "Vanilla interfaces must be represented in mixed machines");
        check(!MachineSurveyModel.classify("minecraft:stone").relevant(), "Terrain must not be mistaken for a machine component");
        check(!MachineSurveyModel.classify("some_mod:shaft").relevant(), "Names in unknown namespaces must not impersonate Create");
        check(MachineSurveyModel.classify("create:mystery_component").roles().equals(List.of("unclassified_create_component")),
                "Unknown Create IDs need an explicit unknown role");
    }

    private static void preserveBlockIndicesAndRejectDiagonals() {
        List<MachineSurveyModel.Component> components = List.of(component(0, 0, 0, 17),
                component(1, 0, 0, 2), component(0, 1, 0, 24), component(-1, -1, 0, 9));
        MachineSurveyModel.Adjacencies result = MachineSurveyModel.adjacent(components, 100);
        check(result.edges().equals(List.of(new MachineSurveyModel.Edge(17, 2, "east"),
                new MachineSurveyModel.Edge(17, 24, "up"))), "Only touching faces should produce one edge per pair with original indices");
        check(result.omitted() == 0, "No omitted edges expected");
    }

    private static void reportDroppedEdgesWithoutChangingRetainedEdges() {
        List<MachineSurveyModel.Component> chain = List.of(component(0, 0, 0, 3),
                component(1, 0, 0, 8), component(2, 0, 0, 42));
        MachineSurveyModel.Adjacencies capped = MachineSurveyModel.adjacent(chain, 1);
        check(capped.edges().equals(List.of(new MachineSurveyModel.Edge(3, 8, "east"))) && capped.omitted() == 1,
                "Bounded graph payload must expose truncation and retain stable block indices");
        MachineSurveyModel.Adjacencies none = MachineSurveyModel.adjacent(chain, 0);
        check(none.edges().isEmpty() && none.omitted() == 2, "Zero-budget graph must still report omitted evidence");
    }

    private static void boundVolumeAgainstUntrustedRadius() {
        check(MachineSurveyModel.boundedRadius(Integer.MAX_VALUE) == 8, "Huge radii must be clamped before iteration");
        check(MachineSurveyModel.boundedRadius(Integer.MIN_VALUE) == 0, "Negative radii must not wrap volume calculations");
        check(MachineSurveyModel.boundedRadius(4) == 4, "Normal radii must be preserved");
    }

    private static MachineSurveyModel.Component component(int x, int y, int z, int index) {
        return new MachineSurveyModel.Component(new MachineSurveyModel.Point(x, y, z), index);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
