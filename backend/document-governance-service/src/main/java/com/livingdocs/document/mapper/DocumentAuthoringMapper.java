package com.livingdocs.document.mapper;

import com.livingdocs.document.model.*;
import com.livingdocs.document.dto.response.*;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentAuthoringMapper {
    DocumentView document(DocumentDetails value);
    AuthoringVersionView version(VersionDetails value);
    DocumentVersionsView page(VersionPage value);
    DocumentEvidenceView evidence(VersionEvidence value);
}
