package com.api.automation.utils;

import java.util.ArrayList;
import java.util.List;

import com.api.automation.pages.Check;

import io.restassured.path.json.JsonPath;

/**
 * Excel "Validate" column - explicit JSON checks per row, separated by ';'
 *
 *   status=1                    equals
 *   msg.panStatus~=VALID        contains (case-insensitive)
 *   msg.error!=null             not equals
 *   result.name=${TESTER_NAME}  variables are resolved (in the path too)
 *   rows.find{it.GROUP_CODE=='${crudCode}'}.GROUP_NAME=${crudName}   read-back after a save / update
 *   rows.find{it.MEMBER_ID==${crudMemberId} && it.TAG_RECORDSTATUS=='A'}=null   record is gone after delete
 */
public class ValidationUtil {

    public static List<Check> validate(String normalizedBody, String validationRule) {

        List<Check> checks = new ArrayList<>();

        if (validationRule == null || validationRule.trim().isEmpty()
                || validationRule.trim().equalsIgnoreCase("NA")) {
            return checks;
        }

        JsonPath json = ResponseHandler.jsonOrNull(normalizedBody);

        for (String rule : splitRules(validationRule)) {

            rule = rule.trim();
            int[] at = operator(rule);
            if (at == null) continue;
            String op = rule.substring(at[0], at[0] + at[1]);

            String path = GlobalStore.resolve(rule.substring(0, at[0]).trim());
            String expected = GlobalStore.resolve(rule.substring(at[0] + at[1]).trim());

            String actual = json == null ? null : ResponseAnalyzer.str(json, path);
            String shown = actual == null ? "null" : actual;

            boolean pass;
            switch (op) {
                case "~=": pass = actual != null && actual.toLowerCase().contains(expected.toLowerCase()); break;
                case "!=": pass = !expected.equals(shown); break;
                default:   pass = expected.equals(shown);
            }

            checks.add(new Check("Validate: " + path + " " + op + " " + expected, expected, shown, pass));
        }

        return checks;
    }

    /** ';' separates rules, except inside { } of a GPath filter. */
    private static List<String> splitRules(String rules) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < rules.length(); i++) {
            char c = rules.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            else if (c == ';' && depth == 0) {
                out.add(rules.substring(start, i));
                start = i + 1;
            }
        }
        out.add(rules.substring(start));
        return out;
    }

    /** Position + length of the comparison operator (~=, !=, =) outside { } and quotes; null if none. */
    private static int[] operator(String rule) {
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '\'' || c == '"') quote = !quote;
            if (quote) continue;
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            else if (depth == 0 && c == '=') {
                if (i > 0 && (rule.charAt(i - 1) == '~' || rule.charAt(i - 1) == '!')) return new int[]{i - 1, 2};
                return new int[]{i, 1};
            }
        }
        return null;
    }
}
