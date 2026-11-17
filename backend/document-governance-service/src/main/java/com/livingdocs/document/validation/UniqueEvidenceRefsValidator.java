package com.livingdocs.document.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class UniqueEvidenceRefsValidator implements ConstraintValidator<UniqueEvidenceRefs, List<UUID>> {
    @Override
    public boolean isValid(List<UUID> values, ConstraintValidatorContext context) {
        return values == null || new HashSet<>(values).size() == values.size();
    }
}
