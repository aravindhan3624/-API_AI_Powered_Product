package com.api.product.core.flow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.api.product.core.model.ApiModel;
import com.api.product.core.model.ApiModel.Endpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Safe synthetic values for request fields that are empty or still hold a placeholder ("string", "sample", ""). Values are obviously
 * artificial (example.test mail domain, published sample formats), never real personal data. Fields that carry secrets, credentials or
 * ids produced by other calls are never touched. Planning changes nothing; the user applies the plan.
 */
public final class SyntheticData {

    public static final class Change {
        public String endpointId;
        public String api;
        public String field;
        public String oldValue;
        public String newValue;
        public String reason;
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> PLACEHOLDERS = Set.of("", "string", "sample", "test", "example", "abc", "xxx", "string1", "null", "undefined", "value", "text");
    private static final Pattern SKIP = Pattern.compile("(?i).*(pass|pwd|secret|token|otp|pin\\b|cvv|credential|apikey|auth|session|signature|username|userid|loginid).*");

    private SyntheticData() {}

    public static List<Change> plan(ApiModel m) {
        List<Change> out = new ArrayList<>();
        Set<String> chained = new java.util.HashSet<>();
        for (ApiModel.Link l : m.links) chained.add(l.toEndpointId + "|" + l.name);
        for (Endpoint e : m.endpoints) {
            if (e.requestBody != null) walk(e, e.requestBody, "", out, chained, false);
        }
        return out;
    }

    /** Applies the planned changes (same plan as {@link #plan}). Returns the number of values written. */
    public static int apply(ApiModel m, List<Change> changes) {
        int n = 0;
        for (Change c : changes) {
            Endpoint e = m.endpoint(c.endpointId);
            if (e == null || e.requestBody == null) continue;
            n += set(e.requestBody, c.field.split("\\."), 0, c.newValue);
        }
        return n;
    }

    private static int set(JsonNode n, String[] path, int i, String value) {
        if (n == null) return 0;
        if (n.isObject() && i < path.length) {
            ObjectNode o = (ObjectNode) n;
            String k = path[i].replace("[0]", "");
            JsonNode cur = o.get(k);
            if (cur == null) return 0;
            if (i == path.length - 1) {
                o.set(k, typed(cur, value));
                return 1;
            }
            return set(path[i].endsWith("[0]") && cur.isArray() && cur.size() > 0 ? cur.get(0) : cur, path, i + 1, value);
        }
        return 0;
    }

    private static JsonNode typed(JsonNode old, String v) {
        if (old != null && old.isNumber() && v.matches("-?\\d+")) return F.numberNode(Long.parseLong(v));
        if (old != null && old.isNumber() && v.matches("-?\\d+\\.\\d+")) return F.numberNode(Double.parseDouble(v));
        return F.textNode(v);
    }

    private static void walk(Endpoint e, JsonNode n, String prefix, List<Change> out, Set<String> chained, boolean inArray) {
        if (n == null) return;
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                String path = prefix.isEmpty() ? f.getKey() : prefix + "." + f.getKey();
                JsonNode v = f.getValue();
                if (v.isValueNode()) {
                    if (SKIP.matcher(f.getKey()).matches() || chained.contains(e.id + "|" + f.getKey())) continue;
                    if (!prefix.isEmpty() && f.getKey().equalsIgnoreCase("name")) continue;   // a nested 'name' is a label, not a person
                    String s = v.isNull() ? "" : v.asText();
                    boolean numericPlaceholder = v.isNumber() && (v.asDouble() == 0 || v.asDouble() == 1 || v.asDouble() == 1.5) && semantic(f.getKey()) != null;
                    boolean ph = PLACEHOLDERS.contains(s.toLowerCase(Locale.ROOT)) || s.matches("<.*>|\\{\\{.*}}") || numericPlaceholder;
                    if (!ph || s.contains("${")) continue;
                    String nv = valueFor(f.getKey(), v);
                    if (nv == null || nv.equals(s)) continue;
                    Change c = new Change();
                    c.endpointId = e.id;
                    c.api = e.method + " " + e.path;
                    c.field = path;
                    c.oldValue = s;
                    c.newValue = nv;
                    c.reason = semantic(f.getKey());
                    out.add(c);
                } else if (v.isObject()) {
                    walk(e, v, path, out, chained, inArray);
                } else if (v.isArray() && v.size() > 0 && v.get(0).isObject()) {
                    walk(e, v.get(0), path + "[0]", out, chained, true);
                }
            }
        }
    }

    /** Short description of the kind of value, or null when the field name says nothing useful. */
    static String semantic(String field) {
        String f = field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (f.matches(".*(e_?mail).*")) return "e-mail address (example.test domain)";
        if (f.matches(".*(mobile|phone|msisdn|contact_?no).*")) return "10-digit mobile number";
        if (f.matches(".*(^|_)pan(_|$).*")) return "PAN-format sample";
        if (f.matches(".*(aadhaar|aadhar|uid_?no).*")) return "12-digit sample number";
        if (f.matches(".*(pin_?code|zip|postal).*")) return "6-digit pincode";
        if (f.matches(".*ifsc.*")) return "IFSC-format sample";
        if (f.matches(".*(account_?(no|number|num)).*")) return "account number sample";
        if (f.matches(".*(dob|birth).*")) return "date of birth";
        if (f.matches(".*(date|_dt$).*")) return "date";
        if (f.matches(".*(amount|price|balance|total|salary|income|fee).*")) return "amount";
        if (f.matches(".*(quantity|qty|count).*")) return "small count";
        if (f.matches(".*(^|_)age(_|$).*")) return "age";
        if (f.matches(".*(first_?name|given_?name).*")) return "first name";
        if (f.matches(".*(last_?name|surname|family_?name).*")) return "last name";
        if (f.matches(".*(full_?name|^name$|customer_?name|holder_?name).*")) return "person name";
        if (f.matches(".*(city|town).*")) return "city";
        if (f.matches(".*(^|_)state(_|$).*")) return "state";
        if (f.matches(".*country.*")) return "country";
        if (f.matches(".*(address|street|line_?1).*")) return "street address";
        if (f.matches(".*(url|link|website).*")) return "URL (example.test)";
        if (f.matches(".*(uuid|guid).*")) return "UUID";
        if (f.matches(".*(remark|comment|note|description|reason).*")) return "free text";
        if (f.matches(".*(title|subject).*")) return "title text";
        if (f.matches(".*(company|organi[sz]ation|employer).*")) return "organisation";
        return null;
    }

    static String valueFor(String field, JsonNode old) {
        String sem = semantic(field);
        if (sem == null) return null;
        switch (sem) {
            case "e-mail address (example.test domain)": return "qa.user${RANDOM:6}@example.test";
            case "10-digit mobile number": return "98${RANDOM:8}";
            case "PAN-format sample": return "ABCDE1234F";
            case "12-digit sample number": return "234567890123";
            case "6-digit pincode": return "560001";
            case "IFSC-format sample": return "ABCD0123456";
            case "account number sample": return "${RANDOM:12}";
            case "date of birth": return "1990-05-20";
            case "date": return "2025-01-15";
            case "amount": return old != null && old.isNumber() ? "1500" : "1500.00";
            case "small count": return "2";
            case "age": return "30";
            case "first name": return "Asha";
            case "last name": return "Rao";
            case "person name": return "Asha Rao";
            case "city": return "Bengaluru";
            case "state": return "Karnataka";
            case "country": return "India";
            case "street address": return "12 Sample Road";
            case "URL (example.test)": return "https://example.test/sample";
            case "UUID": return "123e4567-e89b-12d3-a456-426614174000";
            case "free text": return "Synthetic test note";
            case "title text": return "Synthetic test title";
            case "organisation": return "Example Pvt Ltd";
            default: return null;
        }
    }
}
