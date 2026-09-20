package org.maiwithu.maicraft.intent.persistence;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.intent.Plan;

/** 任一记录表装不下时整份快照都应拒绝，不能保存或恢复一份已经遗漏任务身份的数据。 */
public final class CheckpointCapacityTest {
    public static void main(String[] args) {
        var goal = new Goal("maicraft:wait_for_condition", "等待", null, "{}", "{}", List.of(), List.of());
        var task = new IntentTaskRecord(UUID.randomUUID(), null, goal);
        var plan = Plan.compile(goal, 0);
        var landmark = new IntentRuntime.Landmark("营地", new Goal.WorldPosition(0, 64, 0, "minecraft:overworld"));
        var keys = new LinkedHashMap<String, UUID>();
        for (int i = 0; i <= IntentStateCodec.MAX_REQUEST_KEYS; i++) keys.put("request-" + i, task.externalId());
        // 计划、任务、请求编号和地标分别触及容量，任何一类都不能静默截掉末尾。
        refuses(() -> IntentStateCodec.encode("test", Collections.nCopies(IntentStateCodec.MAX_PLANS + 1, plan),
                List.of(), Map.of(), List.of()));
        refuses(() -> IntentStateCodec.encode("test", List.of(),
                Collections.nCopies(IntentStateCodec.MAX_TASKS + 1, task), Map.of(), List.of()));
        refuses(() -> IntentStateCodec.encode("test", List.of(), List.of(task), keys, List.of()));
        refuses(() -> IntentStateCodec.encode("test", List.of(), List.of(), Map.of(),
                Collections.nCopies(IntentStateCodec.MAX_LANDMARKS + 1, landmark)));

        // 读取旧文件同样不能截掉请求编号，否则调用者的重试可能被当成一件新工作。
        var encoded = IntentStateCodec.encode("test", List.of(), List.of(task), Map.of(), List.of());
        var oversizedKeys = new JsonObject();
        keys.forEach((key, id) -> oversizedKeys.addProperty(key, id.toString()));
        encoded.add("request_keys", oversizedKeys);
        refuses(() -> IntentStateCodec.decode(encoded));
        System.out.println("CheckpointCapacityTest: passed");
    }

    private static void refuses(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("超出容量却返回了不完整的检查点");
        } catch (IllegalArgumentException expected) { }
    }
}
