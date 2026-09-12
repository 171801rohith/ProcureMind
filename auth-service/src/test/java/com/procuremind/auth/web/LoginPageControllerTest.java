package com.procuremind.auth.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The branded sign-in page replaces Spring Security's generated form. What matters is that
 * it still posts the same credentials to the same processing endpoint, and that it reports
 * failures without saying which half of the credentials was wrong.
 *
 * <p>Filters are disabled: this asserts the rendered page, not the security rules, which
 * {@code LoginPageTest} covers end to end against a real database.
 */
@WebMvcTest(LoginPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class LoginPageControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void rendersTheBrandedProcureMindSignInPage() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().string(Matchers.containsString("ProcureMind")))
                .andExpect(content().string(Matchers.containsString("/css/auth.css")));
    }

    @Test
    void postsCredentialsToSpringSecuritysOwnProcessingEndpoint() throws Exception {
        // The form must keep posting username/password to /login; only the markup changed.
        mockMvc.perform(get("/login"))
                .andExpect(content().string(Matchers.containsString("action=\"/login\"")))
                .andExpect(content().string(Matchers.containsString("name=\"username\"")))
                .andExpect(content().string(Matchers.containsString("name=\"password\"")));
    }

    @Test
    void showsAGenericMessageOnFailureSoNeitherFieldIsConfirmed() throws Exception {
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Incorrect username or password.")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("No such user"))));
    }

    @Test
    void confirmsSignOutWhenReturningFromLogout() throws Exception {
        mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("You have been signed out.")));
    }

    @Test
    void showsNoAlertOnAPlainVisit() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Incorrect username or password."))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("You have been signed out."))));
    }
}
