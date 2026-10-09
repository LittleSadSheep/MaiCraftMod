// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 存在 SQLite 文档库里的目标运行存储：按世界身份分开，退出游戏、换世界、崩溃之后都读得回来。
 *
 * <p>只存还没结束的记录（进行中、等回答、暂停）和编号计数：重启后要恢复的只有它们，结束了的记录一结束就从存盘里拿掉，
 * 结果已经经任务事件交给了宿主。一个世界的记录放在同一个文档里，每次修改在一个事务里读改写，编号只增不减。
 *
 * <p>读回时能力已经不在、参数规格对不上的记录不拿去推进：记一条警告说明是哪个目标、为什么读不回，
 * 并从存盘里拿掉，免得每次进世界都卡在同一条坏记录上。
 */
public final class DocumentGoalRunStore implements GoalRunStore {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentGoalRunStore.class);

    /** 文档库里的范围名：目标运行都存这个范围下，与世界记忆等别的用途互不覆盖。 */
    public static final String SCOPE = "goal-runs";

    private static final String DOCUMENT_KEY = "unfinished";
    // 还没结束的目标同时只有寥寥几条（主任务、暂停的、sequence 正在跑的那一步），超出按坏了处理，不做截断。
    private static final int DOCUMENT_LIMIT = 262144;

    private final DocumentStore documents;
    private final String worldKey;
    private final GoalRunCodec codec;

    /** @param registry 读回时按能力的参数规格重新整理参数；进世界时能力清单已经登记好 */
    public DocumentGoalRunStore(DocumentStore documents, String worldKey, AbilityRegistry registry) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.worldKey = Objects.requireNonNull(worldKey, "worldKey");
        this.codec = new GoalRunCodec(Objects.requireNonNull(registry, "registry"));
    }

    @Override public long nextId() {
        long[] next = new long[1];
        modify(book -> {
            next[0] = book.has("next_id") ? book.get("next_id").getAsLong() + 1 : 1;
            book.addProperty("next_id", next[0]);
            return book;
        });
        return next[0];
    }

    @Override public void save(GoalRun run) {
        JsonObject encoded = run.unfinished() ? codec.encode(run) : null;
        modify(book -> {
            JsonObject runs = runs(book);
            String key = Long.toString(run.id());
            // 结束了就从存盘里拿掉：重启后不再有它的事；没结束的整条覆盖成此刻的样子。
            runs.remove(key);
            if (encoded != null) runs.add(key, encoded);
            return book;
        });
    }

    @Override public List<GoalRun> unfinished() {
        JsonObject book = readBook();
        List<GoalRun> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : runs(book).entrySet()) {
            try {
                found.add(codec.decode(entry.getValue().getAsJsonObject()));
            } catch (GoalRunCodec.Unreadable problem) {
                LOG.warn("目标运行 {} 的存盘读不回，不再恢复它：{}", entry.getKey(), problem.getMessage());
                unreadable.add(entry.getKey());
            }
        }
        if (!unreadable.isEmpty()) {
            modify(current -> {
                unreadable.forEach(runs(current)::remove);
                return current;
            });
        }
        // 按编号排就是按下达的先后。
        found.sort(Comparator.comparingLong(GoalRun::id));
        return List.copyOf(found);
    }

    private static JsonObject runs(JsonObject book) {
        if (!book.has("runs")) book.add("runs", new JsonObject());
        return book.getAsJsonObject("runs");
    }

    // 每次修改都整份读出、改好、在文档库的一个事务里写回。
    private void modify(UnaryOperator<JsonObject> change) {
        try {
            documents.update(SCOPE, worldKey, DOCUMENT_KEY, DOCUMENT_LIMIT,
                    text -> change.apply(parse(text)).toString());
        } catch (IOException failure) {
            throw new UncheckedIOException("goal_runs_write_failed", failure);
        }
    }

    private JsonObject readBook() {
        try {
            return parse(documents.read(SCOPE, worldKey, DOCUMENT_KEY, DOCUMENT_LIMIT));
        } catch (IOException failure) {
            throw new UncheckedIOException("goal_runs_read_failed", failure);
        }
    }

    private static JsonObject parse(String text) {
        return text == null || text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
    }
}
