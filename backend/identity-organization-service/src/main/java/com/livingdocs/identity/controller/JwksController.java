package com.livingdocs.identity.controller;

import com.livingdocs.identity.security.JwtKeyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeyManager keys;
    // json web key set: là một tập hợp (mảng) các khóa công khai (public keys) được định dạng dưới dạng JSON
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE) // String APPLICATION_JSON_VALUE = "application/json"
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
                .body(keys.publicJwksJson());
    }
}
