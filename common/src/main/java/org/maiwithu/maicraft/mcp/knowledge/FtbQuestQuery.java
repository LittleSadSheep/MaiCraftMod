// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 查询只决定读取哪一页可见内容；先校验 URI，非法参数不能触发任务书或奖励回调。 */
record FtbQuestQuery(String kind, String id, String rewardPath, int offset, String revision, String filter, String query) {
    static FtbQuestQuery parse(String uri) {
        String[] parts = uri.substring(FtbQuestsKnowledgeSource.PREFIX.length()).split("\\?", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid FTB resource query");
        String[] path = parts[0].split("/", -1); String kind = path[0], id = "", rewardPath = "";
        if (kind.equals("chapter") || kind.equals("quest")) {
            if (path.length < 2 || !path[1].matches("[0-9a-fA-F]{16}")) throw new IllegalArgumentException("Invalid FTB ID");
            id = path[1].toUpperCase(Locale.ROOT);
            if (path.length > 2 && kind.equals("quest") && path[2].equals("rewards")) {
                kind = "rewards";
                if (path.length > 20 || path.length > 3 && !path[3].matches("[0-9a-fA-F]{16}")) throw new IllegalArgumentException("Invalid reward path");
                for (int i = 4; i < path.length; i++) if (!path[i].matches("(0|[1-9][0-9]{0,8})~[0-9a-f]{16}"))
                    throw new IllegalArgumentException("Invalid reward table reference");
                if (path.length > 3) { path[3] = path[3].toUpperCase(Locale.ROOT); rewardPath = String.join("/", Arrays.copyOfRange(path, 3, path.length)); }
            } else if (path.length != 2) throw new IllegalArgumentException("Invalid FTB resource path");
        } else if (path.length != 1 || !Set.of("index", "quests").contains(kind)) throw new IllegalArgumentException("Invalid FTB resource URI");
        int offset = 0; String revision = null, filter = "all", query = ""; Set<String> seen = new HashSet<>();
        if (parts.length == 2) for (String parameter : parts[1].split("&", -1)) {
            String[] pair = parameter.split("=", -1);
            if (kind.equals("quest") || pair.length != 2 || !seen.add(pair[0])) throw new IllegalArgumentException("Invalid FTB query");
            switch (pair[0]) {
                case "offset" -> {
                    if (!pair[1].matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Invalid FTB offset");
                    offset = Integer.parseInt(pair[1]);
                }
                case "revision" -> {
                    if (!pair[1].matches("[0-9a-f]{16}")) throw new IllegalArgumentException("Invalid FTB revision"); revision = pair[1];
                }
                case "filter", "q" -> {
                    if (!kind.equals("chapter") && !kind.equals("quests")) throw new IllegalArgumentException("Only quest lists support filters");
                    if (pair[0].equals("filter")) {
                        if (!Set.of("all", "available", "incomplete", "completed", "claimable").contains(pair[1])) throw new IllegalArgumentException("Invalid FTB filter");
                        filter = pair[1];
                    } else {
                        query = URLDecoder.decode(pair[1], StandardCharsets.UTF_8).strip();
                        if (query.length() > 256) throw new IllegalArgumentException("FTB search text is too long");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown FTB query parameter");
            }
        }
        if (offset > 0 && revision == null) throw new IllegalArgumentException("Use the returned next_uri to continue FTB pages");
        return new FtbQuestQuery(kind, id, rewardPath, offset, revision, filter, query);
    }
    String searchParameters() {
        return (filter.equals("all") ? "" : "&filter=" + filter) + (query.isEmpty() ? "" : "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }
    void requireRevision(String current) {
        if (revision != null && !revision.equals(current)) throw new IllegalArgumentException("FTB results changed; restart the requested list");
    }
}
