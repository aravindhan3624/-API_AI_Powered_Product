package com.api.automation.pages.demo;

import java.util.List;

import com.api.automation.pages.ApiPage;
import com.api.automation.pages.ApiResponse;
import com.api.automation.pages.Checks;
import com.api.automation.pages.PageResult;

/** DEMO project (https://dummyjson.com) - a runnable example of the page pattern. */
public final class DemoPages {

    private DemoPages() {}

    public static List<ApiPage> all() {
        return List.of(new AddUserPage());
    }

    /** POST /users/add - response must echo the request and assign an id. */
    public static class AddUserPage extends ApiPage {

        @Override public String name() { return "Create User"; }

        @Override protected String[] endpointKeys() { return new String[]{"users/add"}; }

        @Override public String expectedResult() {
            return "User is created with a new id and the same first name / last name";
        }

        @Override public PageResult validate(ApiResponse r) {
            return new Checks(r)
                    .notBlank("New user id", "id")
                    .sameAsRequest("First name", "firstName", "firstName")
                    .sameAsRequest("Last name", "lastName", "lastName")
                    .result("Created user id " + r.get("id") + " - " + r.get("firstName") + " " + r.get("lastName"), null);
        }
    }
}
