// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 自家人：实例所有者在配置里列出的、信任的玩家（自己、一起玩的朋友）。
 *
 * <p>自家人放的箱子、AE 网络这类存储，角色可以取用、存放，和自己的一样；拆、改照旧当别人的东西。
 * 名单里每一项可以写玩家名，也可以写 UUID。写名字的要对上在线玩家才知道编号，对上一次就记住；
 * 对不上的当不是自家人——拿不准就不用，宁可少翻一只箱子。
 */
public final class TrustedPlayers {

    /** 一个自家人都没有：没配置时用它，谁的存储都按别人的算。 */
    public static final TrustedPlayers NOBODY = new TrustedPlayers(List.of(), name -> Optional.empty());

    /** 名单里直接写成 UUID 的，统一小写比。 */
    private final Set<String> ids;
    /** 名单里写成玩家名的，统一小写比。 */
    private final Set<String> names;
    /** 玩家名查编号：在线玩家才查得到。 */
    private final Function<String, Optional<String>> nameToId;
    /** 已经对上编号的名字：名字 → 编号（小写）；对上一次就记住，下线了也认。 */
    private final Map<String, String> resolved = new ConcurrentHashMap<>();

    /**
     * @param entries  配置里的名单：玩家名或 UUID
     * @param nameToId 玩家名查编号；查不到给空
     */
    public TrustedPlayers(List<String> entries, Function<String, Optional<String>> nameToId) {
        this.nameToId = Objects.requireNonNull(nameToId, "nameToId");
        this.ids = entries.stream().map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(TrustedPlayers::looksLikeUuid).collect(Collectors.toUnmodifiableSet());
        this.names = entries.stream().map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty() && !looksLikeUuid(entry)).collect(Collectors.toUnmodifiableSet());
    }

    /** 这个玩家编号是不是自家人；名单里写名字的，这时候在线就顺便对上编号。 */
    public boolean includes(String playerId) {
        if (playerId == null) return false;
        String id = playerId.toLowerCase(Locale.ROOT);
        if (ids.contains(id)) return true;
        for (String name : names) {
            if (!resolved.containsKey(name)) {
                nameToId.apply(name).ifPresent(found -> resolved.put(name, found.toLowerCase(Locale.ROOT)));
            }
        }
        return resolved.containsValue(id);
    }

    /** 名单是空的：没有自家人。 */
    public boolean isEmpty() {
        return ids.isEmpty() && names.isEmpty();
    }

    // UUID 的写法：8-4-4-4-12 位十六进制。
    private static boolean looksLikeUuid(String entry) {
        return entry.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
