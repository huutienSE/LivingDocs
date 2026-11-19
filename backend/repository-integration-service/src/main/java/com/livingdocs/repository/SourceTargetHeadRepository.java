package com.livingdocs.repository;

import com.livingdocs.entity.SourceTargetHeadEntity;
import com.livingdocs.entity.SourceTargetKey;
import java.util.Optional;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.MANDATORY)
public interface SourceTargetHeadRepository extends Repository<SourceTargetHeadEntity, SourceTargetKey> {
    Optional<SourceTargetHeadEntity> findByTargetAndAuthorityRepositoryId(SourceTargetKey target, String authorityRepositoryId);
}
