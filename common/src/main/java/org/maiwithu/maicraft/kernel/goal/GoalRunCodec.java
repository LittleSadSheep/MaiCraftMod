// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.param.ParseResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 目标运行的存盘格式：一条还没结束的记录写成 JSON，读回时按能力的参数规格重新整理参数。
 *
 * <p>目标的写法与对外接口里的 goal 一致（ability、purpose、target、parameters、permissions、steps、on_failure），
 * 存的就是 LLM 下达的那件事，读的人不用再学一套格式。能力已经不在清单里、或者参数规格变得对不上时读不回，
 * 抛 {@link Unreadable} 说明原因，由存储如实报出来，不拿半份目标去推进。
 */
final class GoalRunCodec {

    /** 一条存盘记录读不回：能力不在了、参数对不上，或者记录本身坏了。 */
    static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    private final AbilityRegistry registry;

    GoalRunCodec(AbilityRegistry registry) {
        this.registry = registry;
    }

    JsonObject encode(GoalRun run) {
        JsonObject json = new JsonObject();
        json.addProperty("id", run.id());
        json.addProperty("parent_run_id", run.parentRunId());
        json.addProperty("step_of_parent", run.stepOfParent());
        json.addProperty("state", lower(run.state()));
        json.addProperty("step_index", run.stepIndex());
        json.add("goal", goal(run.goal()));
        if (run.question() != null) json.add("question", question(run.question()));
        JsonArray answers = new JsonArray();
        run.answers().forEach(answers::add);
        json.add("answers", answers);
        json.addProperty("started_tick", run.startedTick());
        return json;
    }

    GoalRun decode(JsonObject json) {
        try {
            long id = json.get("id").getAsLong();
            List<String> answers = new ArrayList<>();
            json.getAsJsonArray("answers").forEach(answer -> answers.add(answer.getAsString()));
            return GoalRun.fromSaved(id, readGoal(json.getAsJsonObject("goal")),
                    json.get("parent_run_id").getAsLong(), json.get("step_of_parent").getAsInt(),
                    GoalRunState.valueOf(upper(json.get("state").getAsString())), json.get("step_index").getAsInt(),
                    json.has("question") ? readQuestion(json.getAsJsonObject("question")) : null,
                    answers, json.get("started_tick").getAsLong());
        } catch (Unreadable unreadable) {
            throw unreadable;
        } catch (RuntimeException broken) {
            // 字段缺了、类型不对、枚举名对不上：记录本身坏了，照实说是哪一处。
            throw new Unreadable("存盘记录坏了：" + broken.getClass().getSimpleName()
                    + (broken.getMessage() == null ? "" : "：" + broken.getMessage()));
        }
    }

    private JsonObject goal(Goal goal) {
        JsonObject json = new JsonObject();
        json.addProperty("ability", goal.ability());
        if (goal.purpose() != null) json.addProperty("purpose", goal.purpose());
        if (goal.target() != null) json.add("target", target(goal.target()));
        json.add("parameters", goal.params().toJson());
        json.add("permissions", permissions(goal.permissions()));
        JsonArray steps = new JsonArray();
        goal.steps().forEach(step -> steps.add(goal(step)));
        json.add("steps", steps);
        json.addProperty("on_failure", lower(goal.onFailure()));
        return json;
    }

    private Goal readGoal(JsonObject json) {
        String ability = json.get("ability").getAsString();
        AbilityModule module = registry.find(ability).orElseThrow(() ->
                new Unreadable("能力 " + ability + " 已经不在能力清单里"));
        // 参数按能力此刻的参数规格重新整理：存的是整理过的取值，规格没变时原样读回；变了就如实读不回。
        JsonElement raw = json.get("parameters");
        ParseResult parsed = module.spec().params().parse(raw == null ? null : raw.getAsJsonObject());
        if (!parsed.ok()) {
            throw new Unreadable("能力 " + ability + " 的参数和现在的参数规格对不上：" + parsed.errors());
        }
        List<Goal> steps = new ArrayList<>();
        json.getAsJsonArray("steps").forEach(step -> steps.add(readGoal(step.getAsJsonObject())));
        return new Goal(ability,
                json.has("purpose") ? json.get("purpose").getAsString() : null,
                json.has("target") ? readTarget(json.getAsJsonObject("target")) : null,
                parsed.params(),
                readPermissions(json.getAsJsonObject("permissions")),
                steps,
                Goal.OnFailure.valueOf(upper(json.get("on_failure").getAsString())));
    }

    private static JsonObject target(Target target) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", lower(target.kind()));
        switch (target) {
            case Target.Here here -> { }
            case Target.Seen seen -> json.addProperty("id", seen.id());
            case Target.Landmark landmark -> json.addProperty("name", landmark.name());
            case Target.Position position -> {
                json.addProperty("x", position.x());
                if (position.y() != null) json.addProperty("y", position.y());
                json.addProperty("z", position.z());
                if (position.dimension() != null) json.addProperty("dimension", position.dimension());
            }
            case Target.Player player -> json.addProperty("name", player.name());
            case Target.Direction direction -> {
                json.addProperty("toward", lower(direction.toward()));
                json.addProperty("distance", direction.distance());
            }
            case Target.Previous previous -> {
                if (previous.step() != null) json.addProperty("step", previous.step());
            }
        }
        return json;
    }

    private static Target readTarget(JsonObject json) {
        return switch (TargetKind.valueOf(upper(json.get("kind").getAsString()))) {
            case HERE -> new Target.Here();
            case SEEN -> new Target.Seen(json.get("id").getAsString());
            case LANDMARK -> new Target.Landmark(json.get("name").getAsString());
            case POSITION -> new Target.Position(json.get("x").getAsInt(),
                    json.has("y") ? json.get("y").getAsInt() : null, json.get("z").getAsInt(),
                    json.has("dimension") ? json.get("dimension").getAsString() : null);
            case PLAYER -> new Target.Player(json.get("name").getAsString());
            case DIRECTION -> new Target.Direction(Target.Toward.valueOf(upper(json.get("toward").getAsString())),
                    json.get("distance").getAsInt());
            case PREVIOUS -> new Target.Previous(json.has("step") ? json.get("step").getAsInt() : null);
        };
    }

    private static JsonObject permissions(Permissions permissions) {
        JsonObject json = new JsonObject();
        json.addProperty("change_blocks", lower(permissions.changeBlocks()));
        json.addProperty("fight", lower(permissions.fight()));
        json.addProperty("use_rare_items", permissions.useRareItems());
        json.addProperty("kill_animals", lower(permissions.killAnimals()));
        json.addProperty("survival_needs", lower(permissions.survivalNeeds()));
        JsonArray landmarks = new JsonArray();
        permissions.protectedLandmarks().stream().sorted().forEach(landmarks::add);
        json.add("protected_landmarks", landmarks);
        return json;
    }

    private static Permissions readPermissions(JsonObject json) {
        Set<String> landmarks = new HashSet<>();
        json.getAsJsonArray("protected_landmarks").forEach(name -> landmarks.add(name.getAsString()));
        return new Permissions(
                Permissions.BlockChanges.valueOf(upper(json.get("change_blocks").getAsString())),
                Permissions.Fight.valueOf(upper(json.get("fight").getAsString())),
                json.get("use_rare_items").getAsBoolean(),
                Permissions.AnimalKilling.valueOf(upper(json.get("kill_animals").getAsString())),
                Permissions.SurvivalNeeds.valueOf(upper(json.get("survival_needs").getAsString())),
                landmarks);
    }

    private static JsonObject question(Question question) {
        JsonObject json = new JsonObject();
        json.addProperty("reason", lower(question.reason()));
        json.addProperty("text", question.text());
        JsonArray options = new JsonArray();
        for (Question.Option option : question.options()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", option.id());
            item.addProperty("meaning", option.meaning());
            options.add(item);
        }
        json.add("options", options);
        return json;
    }

    private static Question readQuestion(JsonObject json) {
        List<Question.Option> options = new ArrayList<>();
        json.getAsJsonArray("options").forEach(item -> options.add(new Question.Option(
                item.getAsJsonObject().get("id").getAsString(), item.getAsJsonObject().get("meaning").getAsString())));
        return new Question(Question.Reason.valueOf(upper(json.get("reason").getAsString())),
                json.get("text").getAsString(), options);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
