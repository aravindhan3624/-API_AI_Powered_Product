package com.api.automation.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The project (bank) currently being executed, and the folders that belong to it:
 *
 *   src/test/resources/API_TestData/<PROJECT>/<PROJECT>_testdata.xlsx
 *   src/test/resources/payloads/<PROJECT>/*.json
 *   reports/<PROJECT>/
 */
public final class ProjectContext {

    private static volatile String current;

    private ProjectContext() {}

    public static void set(String project) {
        current = project == null ? null : project.trim().toUpperCase();
    }

    public static String current() {
        return current;
    }

    public static String require() {
        if (current == null) {
            throw new IllegalStateException("No project selected. Use a project runner (e.g. RNSB_Runner).");
        }
        return current;
    }

    public static String baseUrl() {
        return ConfigManager.get("base.url");
    }

    public static String displayName() {
        return ConfigManager.get("display.name", require());
    }

    public static String env() {
        return ConfigManager.get("env", "QA");
    }

    public static String testDataPath() {
        // Backward compatible: an explicit full path still wins
        String explicit = ConfigManager.get("testdata.path");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return Paths.get(
                ConfigManager.get("testdata.base.folder", "src/test/resources/API_TestData"),
                require(),
                ConfigManager.get("testdata.file", require() + "_testdata.xlsx")
        ).toString();
    }

    /** Project payload folder; shared payloads can stay in the base folder. */
    public static Path payloadFolder() {
        return Paths.get(ConfigManager.get("payload.base.folder", "src/test/resources/payloads"), require());
    }

    public static Path sharedPayloadFolder() {
        return Paths.get(ConfigManager.get("payload.base.folder", "src/test/resources/payloads"));
    }

    /** reports/  - parent of every project folder (report.base.folder may also be an absolute / network path) */
    public static Path reportBase() {
        Path base = Paths.get(ConfigManager.get("report.base.folder", "reports"));
        if (!base.isAbsolute()) {
            base = Paths.get(System.getProperty("user.dir")).resolve(base);
        }
        return base.toAbsolutePath().normalize();
    }

    /** reports/<PROJECT> */
    public static Path reportFolder() {
        return reportBase().resolve(require());
    }
}
