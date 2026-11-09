package com.api.automation.pages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.api.automation.pages.common.CommonPages;
import com.api.automation.pages.demo.DemoPages;
import com.api.automation.pages.rnsb.RnsbPages;
import com.api.automation.pages.spcbl.SpcblPages;

/**
 * Finds the page (validation class) for an endpoint of the active project.
 * Project pages are checked first, then pages shared by all projects.
 * When no page matches, the engine falls back to the generic ResponseAnalyzer.
 *
 * New project?  Create pages/<project>/<Project>Pages.java and register it here.
 */
public final class PageRegistry {

    private static final Map<String, List<ApiPage>> PROJECT_PAGES = Map.of(
            "RNSB", RnsbPages.all(),
            "SPCBL", SpcblPages.all(),
            "DEMO", DemoPages.all(),
            "CKYC", com.api.automation.pages.ckyc.CkycPages.all()
    );

    private PageRegistry() {}

    public static ApiPage find(String project, String endpoint) {
        List<ApiPage> candidates = new ArrayList<>(PROJECT_PAGES.getOrDefault(project, List.of()));
        candidates.addAll(CommonPages.all());

        for (ApiPage page : candidates) {
            if (page.matches(endpoint)) return page;
        }
        return null;
    }
}
