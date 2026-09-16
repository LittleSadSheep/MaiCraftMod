package org.maiwithu.maicraft.client.actor;

import java.util.ArrayList;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.intent.IntentRuntime;

import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/**
 * 伤害事件的证据字段与片段合并。
 *
 * <p>存在的理由：旧事件只有"掉了多少有效血"，既说不出攻击者种类，也没有一句本能是否在管；
 * 而且连挨三口会发三条事件，等于把同一件事唤醒三次上层。这里固定两条口径——
 * 事实（谁打的、掉血、当前血量、证据来源）与片段（首次立即报、窗口内合并、收尾给总数）。
 */
public final class DamageEpisodeTest {

    public static void main(String[] args) throws Exception {
        singleHitCarriesActionableEvidence();
        repeatedHitsCoalesceIntoOneEpisode();
        healthDropWithoutPacketIsLabelled();
        System.out.println("DamageEpisodeTest: damage evidence and episode coalescing passed");
    }

    private static void singleHitCarriesActionableEvidence() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var zombie = f.mob(11, 2);
            var signals = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get()
                    .subscribeAttention(e -> signals.add(JsonParser.parseString(e.toString()).getAsJsonObject()))) {
                f.hit(zombie, zombie);
                check(GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20F, 18F),
                        "a real damage packet must reach the monitor");

                check(signals.size() == 1, "one hit publishes exactly one damage event");
                var event = latestEvent(signals);
                check(event.get("type").getAsString().equals("agent.damaged"), "the event keeps its type");
                check(event.get("priority").getAsString().equals("important"),
                        "body damage must stay important rather than background");
                JsonObject data = event.getAsJsonObject("data");
                check("started".equals(data.get("phase").getAsString()), "the first hit opens the episode");
                check("damage_packet".equals(data.get("evidence").getAsString()),
                        "a packet-confirmed hit must say where the evidence came from");
                check(data.getAsJsonObject("cause").get("causing_entity_type_id").getAsString()
                                .equals("minecraft:zombie"),
                        "the event must name the attacker kind, not only the health drop");
                check(data.get("current_health").getAsFloat() == 20F
                                && data.get("effective_health_delta").getAsFloat() == 2F,
                        "health facts must be the observed numbers");
                JsonObject defense = data.getAsJsonObject("defense");
                check("instinct".equals(defense.get("policy").getAsString()) && defense.has("would_engage"),
                        "defense evidence must describe whether the reflex can take over");
                check(!defense.has("retaliating") && !defense.has("engaging"),
                        "the event must not claim the agent is fighting: that state has no evidence here");
                check(data.getAsJsonObject("repeat").get("hits").getAsInt() == 1,
                        "the opening event counts the first hit");
            }
        }
    }

    private static void repeatedHitsCoalesceIntoOneEpisode() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var zombie = f.mob(11, 2);
            var signals = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get()
                    .subscribeAttention(e -> signals.add(JsonParser.parseString(e.toString()).getAsJsonObject()))) {
                // 5 秒窗口内连挨三口：只该有一次上报，否则上层会被同一件事唤醒三次。
                f.hit(zombie, zombie);
                GameplayAttentionMonitor.observeDamagePackets(f.h.player, 20F, 18F);
                f.h.level.time += 20;
                f.hit(zombie, zombie);
                GameplayAttentionMonitor.observeDamagePackets(f.h.player, 18F, 16F);
                f.h.level.time += 20;
                f.hit(zombie, zombie);
                GameplayAttentionMonitor.observeDamagePackets(f.h.player, 16F, 14F);
                check(signals.size() == 1, "hits inside one window must coalesce instead of repeating");

                // 挨打停下超过窗口后收尾一次，交代总次数与总掉血。
                f.h.level.time += 200;
                GameplayAttentionMonitor.settleDamageEpisodes(f.h.player);
                check(signals.size() == 2, "a settled episode reports exactly once more");
                JsonObject closing = latestEvent(signals).getAsJsonObject("data");
                check("finished".equals(closing.get("phase").getAsString()), "the closing event closes the episode");
                check(closing.getAsJsonObject("repeat").get("hits").getAsInt() == 3,
                        "the closing event counts every hit of the episode");
                check(closing.getAsJsonObject("repeat").get("damage_total").getAsFloat() == 6F,
                        "the closing event totals the damage of the episode");
                check(closing.get("effective_health_delta").getAsFloat() == 6F,
                        "the closing event reports the whole episode's health drop");

                GameplayAttentionMonitor.settleDamageEpisodes(f.h.player);
                check(signals.size() == 2, "a settled episode must not be reported twice");

                var second = f.mob(12, 4);
                f.hit(second, second);
                GameplayAttentionMonitor.observeDamagePackets(f.h.player, 14F, 13F);
                check(signals.size() == 3, "a different attacker starts its own episode");
            }
        }
    }

    private static void healthDropWithoutPacketIsLabelled() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var signals = new ArrayList<JsonObject>();
            try (var subscription = IntentRuntime.get()
                    .subscribeAttention(e -> signals.add(JsonParser.parseString(e.toString()).getAsJsonObject()))) {
                // 没收到伤害包、只观察到血量下降时也上报，但证据口径必须写明是"血量下降"。
                GameplayAttentionMonitor.observeHealthDrop(f.h.player, 20F, 17F);
                check(signals.size() == 1, "a health drop without a packet is still reported once");
                JsonObject data = latestEvent(signals).getAsJsonObject("data");
                check("health_drop_without_packet".equals(data.get("evidence").getAsString()),
                        "unconfirmed damage must not be labelled as a damage packet");
                check(!data.getAsJsonObject("cause").has("causing_entity_type_id"),
                        "a health drop alone names no attacker");
            }
        }
    }

    /** 订阅回调收到的是 attention 读取包，事件在 events 数组里。 */
    private static JsonObject latestEvent(ArrayList<JsonObject> signals) {
        var events = signals.get(signals.size() - 1).getAsJsonArray("events");
        check(events.size() == 1, "each signal carries exactly the newly published event");
        return events.get(0).getAsJsonObject();
    }
}
