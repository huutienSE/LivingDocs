package com.livingdocs.spring_boot_baseline;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PingController {

    @GetMapping("api/ping")
    public Map<String, String> ping(){
        return Map.of("status", "ok");
    }
}
