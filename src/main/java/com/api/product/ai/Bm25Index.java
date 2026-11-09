package com.api.product.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small in-memory BM25 text index (no external service). */
public final class Bm25Index {

    public static final class Doc {
        public final String id;
        public final String title;
        public final String text;
        public final String source;
        public final String scope; // "global" or a project name
        final Map<String, Integer> tf = new HashMap<>();
        int len;

        public Doc(String id, String title, String text, String source, String scope) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.source = source;
            this.scope = scope;
        }
    }

    public static final class Hit {
        public final Doc doc;
        public final double score;

        Hit(Doc doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }

    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are",
            "it", "this", "that", "with", "as", "be", "by", "at", "from", "what", "how", "do", "i", "me", "my", "can", "you", "we");

    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgLen;

    public synchronized void add(Doc d) {
        for (String t : tokens(d.title + " " + d.title + " " + d.text)) {
            d.tf.merge(t, 1, Integer::sum);
            d.len++;
        }
        for (String t : d.tf.keySet()) df.merge(t, 1, Integer::sum);
        docs.add(d);
        avgLen = docs.stream().mapToInt(x -> x.len).average().orElse(1);
    }

    public synchronized void removeScope(String scope) {
        docs.removeIf(d -> d.scope.equals(scope));
        df.clear();
        for (Doc d : docs) for (String t : d.tf.keySet()) df.merge(t, 1, Integer::sum);
        avgLen = docs.stream().mapToInt(x -> x.len).average().orElse(1);
    }

    public int size() {
        return docs.size();
    }

    public synchronized List<Hit> search(String query, String project, int k) {
        List<String> q = tokens(query);
        List<Hit> hits = new ArrayList<>();
        int n = docs.size();
        for (Doc d : docs) {
            if (!d.scope.equals("global") && !d.scope.equals(project)) continue;
            double score = 0;
            for (String t : q) {
                Integer f = d.tf.get(t);
                if (f == null) continue;
                double idf = Math.log(1 + (n - df.getOrDefault(t, 0) + 0.5) / (df.getOrDefault(t, 0) + 0.5));
                score += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * d.len / avgLen));
            }
            if (score > 0) hits.add(new Hit(d, score));
        }
        hits.sort((a, b) -> Double.compare(b.score, a.score));
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 2 || STOP.contains(t)) continue;
            out.add(t.length() > 4 && t.endsWith("s") ? t.substring(0, t.length() - 1) : t);
        }
        return out;
    }
}
