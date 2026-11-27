package com.livingdocs.document.service;
import com.livingdocs.document.model.*;
import java.util.UUID;

public interface DocumentSubmitService { SubmitResult submit(UUID version, SubmitCommand command, long revision, String key, SubmitActorContext actor, String userAccessToken, String correlation); }
