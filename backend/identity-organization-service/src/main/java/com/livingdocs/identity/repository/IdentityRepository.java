package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.Identity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdentityRepository extends JpaRepository<Identity, UUID> {

    @Query("select i from Identity i where lower(trim(i.email)) = lower(trim(:email))")
    Optional<Identity> findByNormalizedEmail(@Param("email") String email);
}
