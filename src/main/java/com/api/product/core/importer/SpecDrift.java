package com.api.product.core.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.api.product.core.model.ApiModel.TestCase;

/**
 * Compares a project with a newer specification. Nothing is changed unless {@link #apply} is called, and even then nothing is deleted:
 * removed APIs have their tests parked as DRAFT (tag spec-removed), changed APIs are tagged spec-changed and get the new contract,
 * new APIs arrive as DRAFT with their generated tests.
 */
public final class SpecDrift {

    public static final class Item {
        public String id;
        public String api;
        public String detail;
        public int tests;
    }

    public static final class Report {
        public final List<Item> added = new ArrayList<>();
        public final List<Item> removed = new ArrayList<>();
        public final List<Item> changed = new ArrayList<>();
        public int unchanged;
        public int withoutBaseline;
        public boolean any() {
            return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
        }
    }

    private SpecDrift() {}

    public static Report compare(ApiModel current, ApiModel latest) {
        Report r = new Report();
        Map<String, Endpoint> cur = index(current);
        Map<String, Endpoint> neu = index(latest);
        for (Map.Entry<String, Endpoint> e : neu.entrySet()) {
            Endpoint old = cur.get(e.getKey());
            Endpoint n = e.getValue();
            if (old == null) {
                r.added.add(item(n, "new in the specification", 0));
            } else if (old.specHash == null || old.specHash.isEmpty()) {
                r.withoutBaseline++;
            } else if (!old.specHash.equals(n.specHash)) {
                r.changed.add(item(old, "request or response contract changed", count(current, old.id)));
            } else {
                r.unchanged++;
            }
        }
        for (Map.Entry<String, Endpoint> e : cur.entrySet()) {
            if (!neu.containsKey(e.getKey())) r.removed.add(item(e.getValue(), "no longer in the specification", count(current, e.getValue().id)));
        }
        return r;
    }

    /** Applies the newer specification to {@code current}. Returns a one-line summary. */
    public static String apply(ApiModel current, ApiModel latest) {
        Map<String, Endpoint> cur = index(current);
        Map<String, Endpoint> neu = index(latest);
        int add = 0, park = 0, tag = 0;
        for (Map.Entry<String, Endpoint> e : neu.entrySet()) {
            Endpoint n = e.getValue();
            Endpoint old = cur.get(e.getKey());
            if (old == null) {
                current.endpoints.add(n);
                for (TestCase t : latest.testCases) {
                    if (n.id.equals(t.endpointId)) {
                        t.lifecycle = "DRAFT";
                        current.testCases.add(t);
                    }
                }
                add++;
            } else {
                boolean changed = old.specHash != null && !old.specHash.isEmpty() && !old.specHash.equals(n.specHash);
                old.responseSchema = n.responseSchema;
                old.specKey = n.specKey;
                old.specHash = n.specHash;
                if (changed) {
                    for (TestCase t : current.testCases) {
                        if (old.id.equals(t.endpointId) && !t.tags.contains("spec-changed")) t.tags.add("spec-changed");
                    }
                    tag++;
                }
            }
        }
        for (Map.Entry<String, Endpoint> e : cur.entrySet()) {
            if (neu.containsKey(e.getKey())) continue;
            for (TestCase t : current.testCases) {
                if (e.getValue().id.equals(t.endpointId)) {
                    t.lifecycle = "DRAFT";
                    if (!t.tags.contains("spec-removed")) t.tags.add("spec-removed");
                    park++;
                }
            }
        }
        return add + " APIs added as DRAFT, " + tag + " APIs tagged spec-changed, " + park + " tests of removed APIs parked as DRAFT";
    }

    private static Map<String, Endpoint> index(ApiModel m) {
        Map<String, Endpoint> map = new HashMap<>();
        for (Endpoint e : m.endpoints) map.put(e.id, e);
        return map;
    }

    private static Item item(Endpoint e, String detail, int tests) {
        Item i = new Item();
        i.id = e.id;
        i.api = e.method + " " + (e.specKey != null ? e.specKey.replaceFirst("^\\S+ ", "") : e.path);
        i.detail = detail;
        i.tests = tests;
        return i;
    }

    private static int count(ApiModel m, String endpointId) {
        int n = 0;
        for (TestCase t : m.testCases) if (endpointId.equals(t.endpointId)) n++;
        return n;
    }
}
