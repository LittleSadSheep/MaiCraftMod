// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.production;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.*;
import org.maiwithu.maicraft.core.integration.machine.production.ProductionEvidence.*;

/** Synthetic adapter evidence exercises contracts; it never claims a live machine exists. */
final class ProductionFixture {
    static final Resource IRON = new Resource("items", "iron#fixture"), SHEET = new Resource("items", "sheet#fixture"), RPM = new Resource("kinetic", "rpm");
    static JsonObject manifest() {
        return JsonParser.parseString("""
            {"schema_version":1,
             "nodes":[{"id":"ae_supply","kind":"source","offset":[-4,0,0],"material_policy":"storage_available"},
               {"id":"press","kind":"process","offset":[0,2,0],"recipe_id":"create:pressing/iron_ingot","batches":3},
               {"id":"sorter","kind":"transport","offset":[1,0,0]}, {"id":"ae_return","kind":"sink","offset":[4,0,0]},
               {"id":"drive","kind":"source","offset":[0,2,-2]}],
             "ports":[{"id":"supply","node":"ae_supply","offset":[-4,0,0],"face":"east","medium":"items","direction":"output"},
               {"id":"input","node":"press","offset":[0,0,0],"face":"west","medium":"items","direction":"input"},
               {"id":"output","node":"press","offset":[0,0,0],"face":"east","medium":"items","direction":"output"},
               {"id":"sorter_in","node":"sorter","offset":[1,0,0],"face":"west","medium":"items","direction":"input"},
               {"id":"sorter_out","node":"sorter","offset":[1,0,0],"face":"east","medium":"items","direction":"output"},
               {"id":"sink","node":"ae_return","offset":[4,0,0],"face":"west","medium":"items","direction":"input"},
               {"id":"power","node":"drive","offset":[0,2,-2],"face":"south","medium":"kinetic","direction":"output"},
               {"id":"shaft","node":"press","offset":[0,2,0],"face":"north","medium":"kinetic","direction":"input"}],
             "configurations":[{"id":"finished_only","node":"sorter","operation":"machine.configure","stage":"configure","arguments":{"action":"mekanism.sorter_filter","resource_id":"sheet#fixture"}}],
             "links":[{"id":"feed","from":"supply","to":"input","medium":"items","resource":"iron#fixture","amount":3,"path":[[-4,0,0],[-3,0,0],[-2,0,0],[-1,0,0],[0,0,0]]},
               {"id":"select","from":"output","to":"sorter_in","medium":"items","resource":"sheet#fixture","amount":3,"path":[[0,0,0],[1,0,0]],"configurations":["finished_only"]},
               {"id":"return","from":"sorter_out","to":"sink","medium":"items","resource":"sheet#fixture","amount":3,"path":[[1,0,0],[2,0,0],[3,0,0],[4,0,0]]},
               {"id":"rotation","from":"power","to":"shaft","medium":"kinetic","resource":"rpm","amount":16,"path":[[0,2,-2],[0,2,-1],[0,2,0]]}],
             "target":{"node":"ae_return","medium":"items","resource":"sheet#fixture"},
             "observation":{"window_ticks":200,"minimum_output":3,"minimum_events":3,"max_idle_ticks":100}}
            """).getAsJsonObject();
    }
    static class Verified implements ProductionEvidence {
        static final Check YES = new Check(Status.VERIFIED, "test_native_adapter", "fixture facts only");
        public Binding resolve(Resource selector) { return new Binding(YES,selector,null); }
        public Recipe recipe(String id) { return new Recipe(id, List.of(new Ingredient("ingot", Set.of(IRON), 1, true)),
                List.of(new Output(SHEET, 1, 1)), Map.of(RPM, 16L), Set.of("unobstructed_receiver"), true, "test_recipe_snapshot"); }
        public Check geometry(Node node) { return YES; }
        public Check process(Node node, Recipe recipe) { return YES; }
        public Check port(Port port) { return YES; }
        public Check link(Link link, Port from, Port to) { return YES; }
        public Check topology(Link link, Port from, Port to) { return YES; }
        public Check operational(Link link, Port from, Port to) { return YES; }
        public Check configuration(Configuration configuration) { return YES; }
        public Check configurationAvailable(Configuration configuration) { return YES; }
        public Check supply(Node source, Resource resource, long amount) { return YES; }
        public Check condition(Node node, String condition) { return YES; }
    }
}
