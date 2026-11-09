package com.api.automation.utils;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.util.Iterator;
import java.util.Map;

public class JsonDiffUtil {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static String diff(String baseJson, String currentJson) {
        try {
            if (baseJson == null || baseJson.isBlank()) return "{}";
            if (currentJson == null || currentJson.isBlank()) return "{}";

            JsonNode baseNode = mapper.readTree(baseJson);
            JsonNode currNode = mapper.readTree(currentJson);

            ObjectNode diffNode = mapper.createObjectNode();
            computeDiff("", baseNode, currNode, diffNode);

            return diffNode.isEmpty() ? "{}" : diffNode.toPrettyString();

        } catch (Exception e) {
            return "{}";
        }
    }

    private static void computeDiff(
            String path,
            JsonNode base,
            JsonNode curr,
            ObjectNode out
    ) {

        if (base == null && curr != null) {
            out.set(path, curr);
            return;
        }

        if (base != null && curr == null) {
            out.put(path, "<REMOVED>");
            return;
        }

        if (!base.equals(curr)) {

            if (base.isValueNode() || curr.isValueNode()) {
                out.set(path, curr);
                return;
            }

            if (base.isObject() && curr.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = curr.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = entry.getKey();

                    computeDiff(
                            key,
                            base.get(key),
                            curr.get(key),
                            out
                    );
                }
            } else {
                out.set(path, curr);
            }
        }
    }
}
