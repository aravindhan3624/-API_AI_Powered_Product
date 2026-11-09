package com.api.product.ai;

import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FailureExplainer {
   private static final Pattern VAR = Pattern.compile("\\$\\{([^}?|]+)");

   private FailureExplainer() {
   }

   public static Explanation explain(TestResult r, ApiModel model) {
      Explanation e = new Explanation();
      String remark = r.remark == null ? "" : r.remark;
      e.severity = r.severity == null ? "-" : r.severity;
      switch (r.outcome == null ? "" : r.outcome) {
         case "PASS":
            String var10001 = r.testCaseId;
            e.headline = var10001 + " passed" + (remark.isEmpty() ? "" : " with a remark");
            e.cause = remark.isEmpty() ? "The response matched every expectation." : remark;
            e.owner = "-";
            if (!remark.isEmpty()) {
               e.nextSteps.add("Check whether the standard status code in the rule pack should be adjusted for this API.");
            }

            return e;
         case "BLOCKED":
            return blocked(r, model, e, remark);
         case "NOT_EXECUTED":
            e.headline = r.testCaseId + " was not executed";
            e.cause = remark;
            e.owner = "Tester";
            e.nextSteps.add("Approve the test (DRAFT/REVIEWED tests only run with 'include draft'), or change Run to Y in the source data.");
            e.references.add("failure-playbook.md#NOT_EXECUTED");
            return e;
         default:
            String cat = r.failureCategory == null ? "" : r.failureCategory;
            Integer http = r.httpStatus;
            String var10 = r.testCaseId;
            e.headline = var10 + " failed: " + (r.apiName == null ? r.endpointId : r.apiName);
            switch (cat) {
               case "UNEXPECTED_STATUS":
                  if (r.responseEvidence != null && r.responseEvidence.toLowerCase().contains("locked")) {
                     e.cause = "The login answered that the account is locked after repeated failed attempts, so this step (and every step that needs the session) cannot run.";
                     e.owner = "Environment administrator";
                     e.severity = "high";
                     e.nextSteps.add("Ask the administrator to unlock the test account, then re-run.");
                     e.nextSteps.add("Use a dedicated account for negative login tests and keep the per-run cap on login negatives low (default 4).");
                     e.references.add("failure-playbook.md#Account locked / LOCKOUT_GUARD");
                  } else {
                     e.cause = "A valid request returned HTTP " + http + " instead of " + String.valueOf(r.expectedStatus) + ". " + hint(http);
                     e.owner = http != null && http >= 500 ? "API team" : "Test data / environment first, then API team";
                     e.nextSteps.add("Open the request and response of this test in the report drawer and compare with the specification.");
                     if (http != null && (http == 401 || http == 403)) {
                        e.nextSteps.add("Check that the login step passed and its token/cookie is sent; confirm the user role.");
                     }

                     if (http != null && (http == 400 || http == 422)) {
                        e.nextSteps.add("Compare the body with the current contract: a new mandatory field or a changed format is the usual cause.");
                     }

                     if (http != null && http == 404) {
                        e.nextSteps.add("Check the path and that the referenced record exists in this environment.");
                     }

                     if (http != null && http == 409) {
                        e.nextSteps.add("The data already exists or the state is wrong; use unique test data (${NOW}, ${RANDOM}).");
                     }

                     e.references.add("failure-playbook.md#UNEXPECTED_STATUS");
                  }
                  break;
               case "INVALID_REQUEST_ACCEPTED":
                  var10 = r.category;
                  e.cause = "Invalid input (" + var10 + ") was accepted with HTTP " + http + ". " + (isSecurity(r.category) ? "This is a security control that did not work." : "Validation seems to be missing for this case.");
                  e.owner = "API developer";
                  e.severity = isSecurity(r.category) ? "critical" : "medium";
                  e.nextSteps.add("Confirm with the API owner that the endpoint is not public/lenient by design before logging a defect.");
                  e.nextSteps.add("Attach the redacted request and response as evidence; recommended fix: add " + (isSecurity(r.category) ? "authentication/authorisation checks on the server." : "server-side validation."));
                  e.references.add("failure-playbook.md#INVALID_REQUEST_ACCEPTED");
                  if (r.owasp != null && !r.owasp.isEmpty()) {
                     e.references.add("owasp-api-top10.md (" + r.owasp + ")");
                  }
                  break;
               case "SERVER_ERROR_ON_BAD_INPUT":
                  e.cause = "The server failed with HTTP " + http + " on invalid input instead of rejecting it with a 4xx. This also risks exposing internal details (CWE-209).";
                  e.owner = "API developer";
                  e.nextSteps.add("Reproduce with the shown request; ask for input validation before processing and a clean 4xx error.");
                  e.references.add("failure-playbook.md#SERVER_ERROR_ON_BAD_INPUT");
                  break;
               case "INFORMATION_LEAK":
                  e.cause = "The error response contains exception, SQL or stack-trace text that helps an attacker.";
                  e.owner = "API developer";
                  e.nextSteps.add("Return a generic error message; log details on the server only.");
                  e.references.add("owasp-api-top10.md (API8 Security Misconfiguration)");
                  break;
               case "REFLECTED_INPUT":
                  e.cause = "The script marker was returned unescaped, a possible cross-site scripting issue (CWE-79) if a browser renders it.";
                  e.owner = "API / UI developer";
                  e.nextSteps.add("Encode output for the context it is rendered in; confirm whether any client renders this field as HTML.");
                  break;
               case "ASSERTION_FAILED":
                  e.cause = remark;
                  e.owner = "Tester, then API team";
                  e.nextSteps.add("Decide whether the response changed (contract change) or the expected value is stale; update the assertion or raise a defect.");
                  e.references.add("failure-playbook.md#ASSERTION_FAILED");
                  break;
               default:
                  e.cause = remark.isEmpty() ? "No category was recorded." : remark;
                  e.owner = "Tester";
                  e.nextSteps.add("Open the evidence in the report and compare expected and actual.");
            }

            return e;
      }
   }

   private static Explanation blocked(TestResult r, ApiModel model, Explanation e, String remark) {
      e.headline = r.testCaseId + " is blocked (it was not sent)";
      e.owner = "Tester / environment";
      e.severity = "-";
      e.cause = remark;
      Matcher m = Pattern.compile("Variable not available: \\$\\{([^}]*)}").matcher(remark);
      if (m.find()) {
         String raw = m.group(1).replace("json:", "");
         List<String> producers = new ArrayList();

         for(String name : raw.split("\\|")) {
            producers.addAll(producersOf(model, name.trim().replace("?", "")));
         }

         e.cause = "A value this test needs (" + raw + ") was never produced by an earlier step.";
         if (!producers.isEmpty()) {
            e.nextSteps.add("Fix the step that should create it first: " + String.join(", ", producers) + ". If those passed, check that the response still contains that field (extraction rule).");
         } else {
            e.nextSteps.add("No endpoint in this project extracts '" + raw + "'. Add an Extract rule on the step that returns it, or set it with -Dvar." + raw + "=value.");
         }

         e.references.add("failure-playbook.md#BLOCKED - variable not available");
      } else if (!remark.startsWith("Credentials not configured") && !"AUTH_NOT_CONFIGURED".equals(r.failureCategory)) {
         if ("LOCKOUT_GUARD".equals(r.failureCategory)) {
            e.nextSteps.add("This was skipped on purpose to protect the test account from lockout. Raise -Dlockout.max=N only with a dedicated account.");
            e.references.add("failure-playbook.md#Account locked / LOCKOUT_GUARD");
         } else if (remark.startsWith("Waiting for OTP")) {
            e.nextSteps.add("Run attended and type the OTP in the popup, or pass -Dvar.OTP=<code>.");
            e.references.add("failure-playbook.md#BLOCKED - waiting for OTP");
         } else if (remark.startsWith("Transport error")) {
            e.nextSteps.add("Check VPN/network, host name, proxy and TLS; confirm the environment is up.");
            e.references.add("failure-playbook.md#BLOCKED - transport error");
         } else {
            e.nextSteps.add("Read the reason above and fix the prerequisite; blocked tests are not API failures.");
         }
      } else {
         e.nextSteps.add("Add an auth profile for the endpoint and provide the secret through an environment variable or -Dvar.NAME=value (never in the model file).");
         e.references.add("failure-playbook.md#BLOCKED - credentials not configured / AUTH_NOT_CONFIGURED");
      }

      return e;
   }

   public static List<String> producersOf(ApiModel model, String var) {
      List<String> out = new ArrayList();
      if (model == null) {
         return out;
      } else {
         for(ApiModel.Endpoint ep : model.endpoints) {
            for(ApiModel.Extract x : ep.extract) {
               if (var.equals(x.var)) {
                  out.add(ep.id);
               }
            }

            if (ep.set.containsKey(var)) {
               out.add(ep.id);
            }
         }

         return out;
      }
   }

   private static String hint(Integer http) {
      if (http == null) {
         return "";
      } else if (http == 401) {
         return "401 means the caller is not authenticated.";
      } else if (http == 403) {
         return "403 means the caller is authenticated but not allowed, or the CSRF token is wrong.";
      } else if (http == 404) {
         return "404 means the path or record was not found.";
      } else if (http == 409) {
         return "409 means a conflict with existing data or state.";
      } else if (http == 415) {
         return "415 means the content type is not supported by the endpoint.";
      } else if (http != 422 && http != 400) {
         return http >= 500 ? "A server-side error." : "";
      } else {
         return "The server rejected the body as invalid.";
      }
   }

   private static boolean isSecurity(String c) {
      return c != null && (c.equals("NO_SESSION") || c.equals("TAMPERED_TOKEN") || c.equals("TAMPERED_CSRF") || c.equals("OTHER_USER") || c.equals("TAMPERED_ENCRYPTION") || c.equals("WRONG_PASSWORD"));
   }

   public static final class Explanation {
      public String headline;
      public String cause;
      public String owner;
      public String severity;
      public List<String> nextSteps = new ArrayList();
      public List<String> references = new ArrayList();
   }
}
