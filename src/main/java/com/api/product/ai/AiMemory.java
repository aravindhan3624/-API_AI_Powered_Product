package com.api.product.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.api.product.core.store.ProjectStore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * What the assistant learns about a project from the people using it (no model training):
 * - dismissed suggestions are not offered again,
 * - accepted suggestions raise confidence,
 * - expected status codes a tester sets for a category become the default for future generated tests.
 * Stored in projects/NAME/ai-memory.json so it travels with the project.
 */
public final class AiMemory {

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        public List<String> dismissed = new ArrayList<>();                 // endpointId|category
        public Map<String, Integer> accepted = new HashMap<>();            // category -> count
        public Map<String, Map<String, Integer>> statusVotes = new HashMap<>(); // category -> "400,422" -> count
    }

    private final ProjectStore store;

    public AiMemory(ProjectStore store) {
        this.store = store;
    }

    private Path file(String project) {
        return store.dir(project).resolve("ai-memory.json");
    }

    public synchronized Data load(String project) {
        try {
            if (Files.exists(file(project))) return M.readValue(file(project).toFile(), Data.class);
        } catch (IOException ignored) {
            // start fresh
        }
        return new Data();
    }

    private void save(String project, Data d) {
        try {
            Files.createDirectories(file(project).getParent());
            M.writeValue(file(project).toFile(), d);
        } catch (IOException ignored) {
            // memory is best effort
        }
    }

    public synchronized void dismiss(String project, String endpointId, String category) {
        Data d = load(project);
        String key = endpointId + "|" + category;
        if (!d.dismissed.contains(key)) d.dismissed.add(key);
        save(project, d);
    }

    public synchronized void accepted(String project, String category) {
        Data d = load(project);
        d.accepted.merge(category, 1, Integer::sum);
        save(project, d);
    }

    public synchronized void voteStatus(String project, String category, List<Integer> status) {
        if (status == null || status.isEmpty()) return;
        Data d = load(project);
        d.statusVotes.computeIfAbsent(category, k -> new HashMap<>())
                .merge(status.toString().replaceAll("[\\[\\] ]", ""), 1, Integer::sum);
        save(project, d);
    }

    public boolean isDismissed(Data d, String endpointId, String category) {
        return d.dismissed.contains(endpointId + "|" + category);
    }

    /** The status list testers chose at least twice for this category, or empty. */
    public List<Integer> learnedStatus(Data d, String category) {
        Map<String, Integer> votes = d.statusVotes.get(category);
        List<Integer> out = new ArrayList<>();
        if (votes == null) return out;
        votes.entrySet().stream().filter(e -> e.getValue() >= 2)
                .max(Map.Entry.comparingByValue()).ifPresent(e -> {
                    for (String s : e.getKey().split(",")) {
                        try {
                            out.add(Integer.parseInt(s));
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                });
        return out;
    }
}
