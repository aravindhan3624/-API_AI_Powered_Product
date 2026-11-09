package com.api.product;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.testng.annotations.Test;

import com.api.product.core.importer.OpenApiModelImporter;
import com.api.product.core.importer.PostmanModelImporter;
import com.api.product.core.model.ApiModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Auto-detects Postman v2.1 or OpenAPI/Swagger JSON.
 * mvn -q test -Dsuite=suites/testng-IMPORT.xml -Dsource=<file> -Dout=projects/X/model.json [-Dlifecycle=DRAFT|ACTIVE]
 * (-Dcollection=... is accepted as an alias of -Dsource)
 */
public class ImportPostmanTest {

    @Test
    public void importSource() throws Exception {
        Path out = Paths.get(System.getProperty("out"));
        ObjectMapper m = new ObjectMapper();
        ApiModel model;
        if (System.getProperty("excel") != null) {
            // legacy project: -Dexcel=<xlsx> -Dproject=CKYC [-Dconfig=src/test/resources/config.properties] [-Dpayloads=<dir>]
            String proj = System.getProperty("project");
            model = com.api.product.core.importer.ExcelProjectImporter.importProject(proj,
                    Paths.get(System.getProperty("config", "src/test/resources/config.properties")),
                    Paths.get(System.getProperty("excel")),
                    Paths.get(System.getProperty("payloads", "src/test/resources/payloads/" + proj)));
        } else if (OpenApiModelImporter.looksLikeOpenApi(m.readTree(Paths.get(System.getProperty("source", System.getProperty("collection"))).toFile()))) {
            Path in = Paths.get(System.getProperty("source", System.getProperty("collection")));
            model = OpenApiModelImporter.importSpec(in, System.getProperty("lifecycle", "DRAFT"));
        } else {
            model = PostmanModelImporter.importCollection(Paths.get(System.getProperty("source", System.getProperty("collection"))));
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        m.enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), model);
        System.out.println("[IMPORT] " + model.api.name + " (" + model.api.source.type + "): endpoints=" + model.endpoints.size()
                + " tests=" + model.testCases.size() + " baseUrl=" + model.environments.get(0).baseUrl
                + " warnings=" + model.api.source.warnings.size() + " -> " + out);
        model.api.source.warnings.stream().limit(8).forEach(w -> System.out.println("[IMPORT]   warn: " + w));
    }
}
