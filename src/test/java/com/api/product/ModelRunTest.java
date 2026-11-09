package com.api.product;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.api.product.core.engine.ModelRunner;
import com.api.product.core.engine.RulePack;
import com.api.product.core.engine.TestResult;
import com.api.product.core.model.ApiModel;
import com.api.product.core.report.ReportGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Runs ANY project described by a model JSON - no per-project Java - and writes the project-wise HTML + Excel report.
 *   mvn -q test -Dsuite=suites/testng-MODEL.xml -Dmodel=projects/DEMO/model.json
 *       [-Denv=DEMO] [-Dinclude.draft=true] [-Dreport.sheets=Summary,Modules,Failures] [-Dfail.build=true]
 * Outcome FAIL is a finding, not a framework error: the build fails on FAIL only when -Dfail.build=true.
 */
public class ModelRunTest {

    @Test
    public void runModel() throws Exception {
        Path modelFile = Paths.get(System.getProperty("model", "projects/DEMO/model.json"));
        Path rulesFile = Paths.get(System.getProperty("rules", "src/test/resources/rules/api-test-rules.properties"));
        ObjectMapper m = new ObjectMapper();
        ApiModel model = m.readValue(modelFile.toFile(), ApiModel.class);
        RulePack rules = RulePack.load(rulesFile);

        ModelRunner runner = new ModelRunner(model, rules, System.getProperty("env"),
                Boolean.getBoolean("include.draft"));
        List<TestResult> results = runner.run();

        Path data = Paths.get("reports", "model-runs", model.api.name + "_" + runner.runId());
        Files.createDirectories(data);
        m.enable(SerializationFeature.INDENT_OUTPUT).writeValue(data.resolve("results.json").toFile(), results);

        ReportGenerator.Meta meta = new ReportGenerator.Meta();
        meta.project = System.getProperty("project", model.api.name);
        meta.environment = runner.environmentName();
        meta.baseUrl = runner.baseUrl();
        meta.source = model.api.source.type + (model.api.source.ref == null ? "" : " - " + model.api.source.ref);
        meta.runId = runner.runId();
        meta.ruleVersion = runner.ruleVersion();
        ReportGenerator.Output out = ReportGenerator.write(ReportGenerator.defaultRoot(), meta, results,
                System.getProperty("report.sheets"));

        Map<String, Integer> count = new TreeMap<>();
        for (TestResult r : results) count.merge(r.outcome, 1, Integer::sum);
        System.out.println("[MODEL-RUN] " + meta.project + " " + runner.runId() + " " + count);
        System.out.println("[MODEL-RUN] HTML  : " + out.html.toAbsolutePath());
        System.out.println("[MODEL-RUN] EXCEL : " + out.excel.toAbsolutePath());
        System.out.println("[MODEL-RUN] INDEX : " + out.index.toAbsolutePath());
        if (Boolean.getBoolean("fail.build")) {
            Assert.assertEquals(count.getOrDefault("FAIL", 0).intValue(), 0, "Failed tests: see " + out.html);
        }
    }
}
