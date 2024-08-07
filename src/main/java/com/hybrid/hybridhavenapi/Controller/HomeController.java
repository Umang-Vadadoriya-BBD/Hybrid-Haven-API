package com.hybrid.hybridhavenapi.Controller;

import com.hybrid.hybridhavenapi.Config.GithubTokenAuthentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String test(){
        return "Welcome To Hybrid Haven API Version 2.2.7";
    }

    @GetMapping("/auth/code")
    public String token(@RequestParam("code") String code) {
        GithubTokenAuthentication githubTokenAuthentication = new GithubTokenAuthentication();

        return githubTokenAuthentication.generateToken(code);
    }
}
