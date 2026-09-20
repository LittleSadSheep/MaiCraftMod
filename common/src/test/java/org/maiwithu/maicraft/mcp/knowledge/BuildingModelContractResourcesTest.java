// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.knowledge;

import com.google.gson.JsonParser;
import java.util.HashSet;
import org.maiwithu.maicraft.core.blueprint.BuildingModelContract;
import static org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibraryTest.*;

/** 不进入世界也能发现契约，固定Schema地址返回完整JSON；未知历史版本不能被替换成当前版本。 */
public final class BuildingModelContractResourcesTest {
    public static void main(String[] args) {
        var library = KnowledgeLibrary.offline(); var snapshot = BuildingModelContract.current();
        var uris = new HashSet<String>(); var listing = request("list");
        while (true) {
            var page = library.request(listing);
            page.getAsJsonArray("resources").forEach(value -> uris.add(value.getAsJsonObject().get("uri").getAsString()));
            if (!page.has("nextCursor")) break; listing.add("cursor",page.get("nextCursor"));
        }
        check(uris.contains(BuildingModelContract.INDEX_URI) && uris.contains(snapshot.schemaUri()),"目录与固定版本Schema均须通过标准资源列表发现");
        var index = JsonParser.parseString(library.read(BuildingModelContract.INDEX_URI).text()).getAsJsonObject();
        check(index.get("protocol_version").getAsInt() == 1 && index.get("revision").getAsString().equals(snapshot.revision()),"资源目录外壳及版本必须与运行时契约一致");
        check(index.getAsJsonArray("resources").isEmpty(),"接入目录不伪造未编写或未验证的教程");
        var read = request("read"); read.addProperty("uri",index.get("design_schema_uri").getAsString());
        var contents = library.request(read).getAsJsonArray("contents");
        check(contents.size() == 1 && contents.get(0).getAsJsonObject().get("text").getAsString().equals(snapshot.schemaText()),"资源读取须返回完整Schema而非摘要或截断片段");
        for (String uri : new String[]{BuildingModelContract.SCHEMA_PREFIX+"0".repeat(64),BuildingModelContract.INDEX_URI+"/unknown"}) {
            try { library.read(uri); throw new AssertionError("未知版本被静默指向当前契约"); }
            catch (KnowledgeException expected) { check(expected.code() == -32002,"未知版本返回资源不存在"); }
        }
        System.out.println("BuildingModelContractResourcesTest: discoverable full schema and immutable URI semantics passed");
    }
}
