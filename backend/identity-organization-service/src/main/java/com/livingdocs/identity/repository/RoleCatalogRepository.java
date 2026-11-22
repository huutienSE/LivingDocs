package com.livingdocs.identity.repository;

import com.livingdocs.identity.entity.RoleCatalog;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleCatalogRepository extends JpaRepository<RoleCatalog, String> {
}
