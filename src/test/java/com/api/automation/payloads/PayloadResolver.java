package com.api.automation.payloads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Payload cell formats (Excel "Payload" column):
 *   NA                         -> no body (GET)
 *   {...}                      -> inline JSON
 *   dl.json                    -> file from payloads/<PROJECT>/ (falls back to payloads/)
 *   a.json|b.json              -> several payloads, one test each
 *   [ {...}, {...} ]           -> one test per element (case-1, case-2 ...)
 *   {"valid":{...}, "negative":[{...},{...}]}
 *                              -> labelled tests; "negative"/"invalid" ones must be REJECTED by the API
 */
public class PayloadResolver {

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Only these top-level keys turn an object into a group of test cases. */
    private static final Set<String> GROUP_KEYS = Set.of("valid", "positive", "negative", "invalid");

    public static List<ResolvedPayload> resolve(String payloadCell, String payloadFolder) {
        return resolve(payloadCell, payloadFolder, null);
    }

    public static List<ResolvedPayload> resolve(String payloadCell, String payloadFolder, String fallbackFolder) {

        List<ResolvedPayload> result = new ArrayList<>();

        if (payloadCell == null || payloadCell.trim().isEmpty()
                || payloadCell.equalsIgnoreCase("NA")
                || payloadCell.equalsIgnoreCase("N/A")
                || payloadCell.equalsIgnoreCase("null")) {

            // No payload (GET case)
            result.add(new ResolvedPayload("default", ""));
            return result;
        }

        // Support multiple sources separated by |  (only when the cell is not raw JSON)
        String trimmed = payloadCell.trim();
        String[] parts = (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<"))
                ? new String[]{trimmed}
                : trimmed.split("\\|");

        for (String part : parts) {
            part = part.trim();

            String json;
            try {
                // XML body: a .xml file or raw XML in the cell -> sent as it is (application/xml)
                if (part.endsWith(".xml") || part.startsWith("<")) {
                    String xml = part.startsWith("<") ? part : null;
                    if (xml == null) {
                        Path path = Paths.get(payloadFolder, part);
                        if (!Files.exists(path) && fallbackFolder != null) path = Paths.get(fallbackFolder, part);
                        xml = Files.readString(path);
                    }
                    result.add(new ResolvedPayload("default", xml.trim()));
                    continue;
                }
                if (part.endsWith(".json")) {
                    Path path = Paths.get(payloadFolder, part);
                    if (!Files.exists(path) && fallbackFolder != null) {
                        path = Paths.get(fallbackFolder, part);
                    }
                    json = Files.readString(path);
                } else {
                    json = part;
                }

                parseJson(json, result);

            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve payload: " + part + " (" + e.getMessage() + ")", e);
            }
        }

        if (result.isEmpty()) {
            result.add(new ResolvedPayload("default", ""));
        }

        return result;
    }

    private static void parseJson(String json, List<ResolvedPayload> out) throws Exception {

        JsonNode root = mapper.readTree(json);

        // Case 1: Array at root
        if (root.isArray()) {
            int i = 1;
            for (JsonNode node : root) {
                out.add(new ResolvedPayload("case-" + i++, node.toString()));
            }
            return;
        }

        // Case 2: Object whose keys are ALL valid/negative/positive/invalid
        if (root.isObject() && root.size() > 0 && allGroupKeys(root)) {

            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode val = entry.getValue();

                if (val.isObject()) {
                    out.add(new ResolvedPayload(key, val.toString()));
                } else if (val.isArray()) {
                    int i = 1;
                    for (JsonNode n : val) {
                        out.add(new ResolvedPayload(key + "-" + i++, n.toString()));
                    }
                }
            }
            return;
        }

        // Case 3: Plain single object (nested objects/arrays are kept intact)
        out.add(new ResolvedPayload("default", root.toString()));
    }

    private static boolean allGroupKeys(JsonNode root) {
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            if (!GROUP_KEYS.contains(names.next().toLowerCase())) return false;
        }
        return true;
    }
}
