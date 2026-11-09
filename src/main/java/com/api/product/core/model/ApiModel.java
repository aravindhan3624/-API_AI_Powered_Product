package com.api.product.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Normalized API model v1.0 - mirrors docs/schemas/api-model.v1.schema.json. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiModel {

    public String modelVersion = "1.0";
    public Api api = new Api();
    public List<Environment> environments = new ArrayList<>();
    public Envelope envelope;
    /** Regexes (case-insensitive, matched against 'module | name | path'): endpoints that get no generated negatives. */
    public List<String> negativeExclude = new ArrayList<>();
    public List<AuthProfile> authProfiles = new ArrayList<>();
    public List<Endpoint> endpoints = new ArrayList<>();
    public List<TestCase> testCases = new ArrayList<>();
    /** Data dependencies between APIs: a value produced by one call and used by another. */
    public List<Link> links = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Api {
        public String name;
        public String version;
        public Source source = new Source();
        /** Response contract checking on valid requests: off | warn (pass with a remark) | fail. */
        public String contractMode = "warn";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Source {
        public String type = "manual";
        public String ref;
        public List<String> warnings = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Environment {
        public String name;
        public String baseUrl;
        public List<String> scopeAllowlist = new ArrayList<>();
        public Map<String, String> variables = new LinkedHashMap<>();
    }

    /** Request/response encryption plug-in. type: none | hybrid-rsa-aes-gcm (server public key is not a secret). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Envelope {
        public String type = "none";
        public String serverPublicKey;
        public List<String> skipPaths = new ArrayList<>();
    }

    /**
     * Authentication profile. Values may use ${var} / ${ENV:NAME}; never store literal secrets in shared models.
     * type: none | basic (username,password) | bearer (token) | apikey (name,value,in=header|query)
     *       | login (loginEndpointId, tokenVar, headerName, headerTemplate)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthProfile {
        public String id;
        public String type = "none";
        public String username;
        public String password;
        public String token;
        public String name;
        public String value;
        public String in = "header";
        public String loginEndpointId;
        public String tokenVar = "token";
        public String headerName = "Authorization";
        public String headerTemplate = "Bearer ${token}";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Endpoint {
        public String id;
        public String name;
        /** Module / folder / tag used for module-wise reporting. */
        public String module;
        public String method = "GET";
        public String path;
        public String contentType;
        /** Id of an entry in authProfiles; applied before mutations. */
        public String authProfileRef;
        public Map<String, String> headers = new LinkedHashMap<>();
        public Map<String, String> query = new LinkedHashMap<>();
        public JsonNode requestBody;
        /** Variables assigned before the call (values may use ${NOW:..}, ${RANDOM:n}, other variables). */
        public Map<String, String> set = new LinkedHashMap<>();
        public List<Extract> extract = new ArrayList<>();
        public boolean idempotent = true;
        public Integer timeoutMs;
        /** WebSocket (method WS) only: how many text messages to wait for after sending (default 1). */
        public Integer expectMessages;
        /** Dereferenced JSON schema of the first 2xx JSON response (from the specification), or null. */
        public JsonNode responseSchema;
        /** "METHOD /templated/path" in the specification and a hash of the operation's contract (for drift detection). */
        public String specKey;
        public String specHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {
        public String id;
        /** Variable that carries the value. */
        public String var;
        public String fromEndpointId;
        /** One or more JSON paths separated by | (first one found in the response wins). */
        public String jsonPath;
        public String toEndpointId;
        /** path | query | header | body */
        public String where = "path";
        /** Parameter, header or body field name in the consuming API. */
        public String name;
        /** auto | manual */
        public String origin = "auto";
        /** high (confirmed by the response schema) | medium (name based guess) */
        public String confidence = "medium";
        public boolean enabled = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Extract {
        public String var;
        public String jsonPath;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TestCase {
        public String id;
        public String endpointId;
        /** POSITIVE or a key of the rule pack (NO_SESSION, NOT_EXISTING ...) or MISSING_FIELD etc. */
        public String category = "POSITIVE";
        public String title;
        public Mutation mutation;
        public Expected expected = new Expected();
        public String lifecycle = "ACTIVE";
        public String origin = "manual";
        /** Id of the positive test this negative was derived from (its data is the baseline). */
        public String basedOn;
        public int version = 1;
        public List<String> tags = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Mutation {
        public List<String> removeHeaders = new ArrayList<>();
        public List<String> removeQuery = new ArrayList<>();
        public Map<String, String> setQuery = new LinkedHashMap<>();
        public Map<String, String> setHeaders = new LinkedHashMap<>();
        public List<String> removeBodyFields = new ArrayList<>();
        public Map<String, JsonNode> setBodyFields = new LinkedHashMap<>();
        public String method;
        public String path;
        /** Replaces the whole request body (XML / non-object bodies); may use ${...}. */
        public String bodyOverride;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Expected {
        /** Overrides the rule-pack default when non-empty. */
        public List<Integer> status = new ArrayList<>();
        public List<Assertion> assertions = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Assertion {
        /** jsonPathExists | jsonPathEquals | maxLatencyMs | bodyNotContains */
        public String type;
        public String path;
        public JsonNode expected;
    }

    public AuthProfile authProfile(String id) {
        if (id == null) return null;
        for (AuthProfile a : authProfiles) if (id.equals(a.id)) return a;
        return null;
    }

    public Endpoint endpoint(String id) {
        for (Endpoint e : endpoints) if (e.id != null && e.id.equals(id)) return e;
        return null;
    }
}
