// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 读 goal：宽松写法整理并说明、错误一次报全、能力名写错给相近的名字、steps 只给按顺序做事的能力。 */
class GoalReaderTest {

    private final GoalReader reader;

    GoalReaderTest() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(ToolTestAbility.use());
        registry.register(ToolTestAbility.sleep());
        registry.register(ToolTestAbility.sequence());
        reader = new GoalReader(registry);
    }

    private GoalReader.Reading read(String json) {
        return reader.read(JsonParser.parseString(json));
    }

    private static List<String> fields(GoalReader.Reading reading) {
        return reading.errors().stream().map(FieldError::field).toList();
    }

    @Test
    void readsAWholeGoalAndNotesEveryNormalization() {
        GoalReader.Reading reading = read("""
                {"ability": "use", "purpose": "剪那只羊", "target": {"kind": "SEEN", "id": "E8"},
                 "parameters": {"item": "minecraft:shears", "count": "2"},
                 "permissions": {"fight": "self_defense", "survival_needs": true}}""");

        assertTrue(reading.ok(), reading.errors()::toString);
        Goal goal = reading.goal();
        assertEquals("maicraft:use", goal.ability());
        assertEquals(new Target.Seen("e8"), goal.target());
        assertEquals(2, goal.params().integer("count"));
        assertEquals(Permissions.Fight.SELF_DEFENSE, goal.permissions().fight());
        assertEquals(Permissions.BlockChanges.NATURAL, goal.permissions().changeBlocks());
        assertEquals(4, reading.notes().size(), reading.notes()::toString);
    }

    @Test
    void namesTheMissingModWhenTheAbilityNeedsOne() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> false);
        registry.register(ToolTestAbility.needsMods("maicraft:machine_probe", "create"));
        GoalReader.Reading reading = new GoalReader(registry).read(JsonParser.parseString("""
                {"ability": "machine_probe"}"""));

        assertEquals(ErrorCode.UNKNOWN_ABILITY, reading.code());
        assertTrue(reading.errors().getFirst().message().contains("需要模组 create"), reading.errors()::toString);
    }

    @Test
    void reportsEveryMistakeInOneGo() {
        GoalReader.Reading reading = read("""
                {"ability": "maicraft:use", "target": {"kind": "landmark", "name": "家"},
                 "parameters": {"count": 99}, "permissions": {"fight": "ninja"}, "speed": 3}""");

        assertFalse(reading.ok());
        assertEquals(ErrorCode.INVALID_PARAMETER, reading.code());
        assertEquals(List.of("goal.speed", "goal.target.kind", "goal.parameters.count", "goal.permissions.fight"),
                fields(reading));
    }

    @Test
    void misspelledAbilitiesGetSimilarNamesAndTheRestIsStillChecked() {
        GoalReader.Reading reading = read("""
                {"ability": "slep", "target": {"kind": "seen"}}""");

        assertEquals(ErrorCode.UNKNOWN_ABILITY, reading.code());
        assertTrue(reading.errors().get(0).expected().contains("maicraft:sleep"), reading.errors()::toString);
        assertTrue(fields(reading).contains("goal.target.id"));
        assertNull(reading.goal());
    }

    @Test
    void parametersWrittenOneLevelTooHighAreMovedIn() {
        GoalReader.Reading reading = read("""
                {"ability": "maicraft:use", "count": 3}""");

        assertTrue(reading.ok(), reading.errors()::toString);
        assertEquals(3, reading.goal().params().integer("count"));
        assertTrue(reading.notes().get(0).contains("goal.parameters.count"));
    }

    @Test
    void stepsGoOnlyToAbilitiesThatDoThingsInOrder() {
        GoalReader.Reading rejected = read("""
                {"ability": "maicraft:use", "steps": [{"ability": "maicraft:sleep"}]}""");
        GoalReader.Reading missing = read("""
                {"ability": "maicraft:sequence"}""");
        GoalReader.Reading accepted = read("""
                {"ability": "maicraft:sequence", "steps": [
                   {"ability": "maicraft:use", "parameters": {"count": 1}},
                   {"ability": "sleep", "on_failure": "continue"}]}""");

        assertEquals(List.of("goal.steps"), fields(rejected));
        assertEquals(List.of("goal.steps"), fields(missing));
        assertTrue(accepted.ok(), accepted.errors()::toString);
        assertEquals(2, accepted.goal().steps().size());
        assertEquals(Goal.OnFailure.CONTINUE, accepted.goal().steps().get(1).onFailure());
    }

    @Test
    void stepsKeepTheWholeThingsPermissionsUnlessTheyOverrideAField() {
        GoalReader.Reading reading = read("""
                {"ability": "sequence", "permissions": {"fight": "self_defense", "protected_landmarks": ["家"]},
                 "steps": [
                   {"ability": "use"},
                   {"ability": "use", "permissions": {"change_blocks": "none", "protected_landmarks": ["麦田"]}}]}""");

        assertTrue(reading.ok(), reading.errors()::toString);
        Permissions plain = reading.goal().steps().get(0).permissions();
        Permissions own = reading.goal().steps().get(1).permissions();
        // 没写许可的一步整份沿用；写了的一步只改自己写的字段，保护地标两边合在一起。
        assertEquals(Permissions.Fight.SELF_DEFENSE, plain.fight());
        assertEquals(Set.of("家"), plain.protectedLandmarks());
        assertEquals(Permissions.Fight.SELF_DEFENSE, own.fight());
        assertEquals(Permissions.BlockChanges.NONE, own.changeBlocks());
        assertEquals(Set.of("家", "麦田"), own.protectedLandmarks());
    }

    @Test
    void mistakesInsideStepsCarryTheirPath() {
        GoalReader.Reading reading = read("""
                {"ability": "maicraft:sequence", "steps": [
                   {"ability": "maicraft:use"}, {"ability": "maicraft:use", "parameters": {"count": 0}}]}""");

        assertEquals(List.of("goal.steps[1].parameters.count"), fields(reading));
    }
}
