package com.livingdocs.identity.service;

import java.util.UUID;

public interface LoginService {

    UUID verifyCredentials(String email, String rawPassword);
}
