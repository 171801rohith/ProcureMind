package com.procuremind.auth.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the branded sign-in page that replaces Spring Security's generated default form.
 *
 * <p>This is presentation only. The credentials are still posted to Spring Security's own
 * {@code /login} processing endpoint, still checked by {@code JpaUserDetailsService} against
 * BCrypt hashes, and the browser session it establishes is still what the Authorization
 * Code + PKCE flow builds on. Nothing about the authentication mechanism changes.
 */
@Controller
public class LoginPageController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        org.springframework.ui.Model model) {
        // Deliberately generic: never reveal whether the username or the password was wrong.
        model.addAttribute("errorMessage", error != null ? "Incorrect username or password." : null);
        model.addAttribute("logoutMessage", logout != null ? "You have been signed out." : null);
        return "login";
    }
}
