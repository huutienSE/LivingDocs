package com.livingdocs.demob;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IdentityController {

    @GetMapping("/api/identity")
    public Map<String, String> identity() {
        return Map.of("service", "B");
    }
}