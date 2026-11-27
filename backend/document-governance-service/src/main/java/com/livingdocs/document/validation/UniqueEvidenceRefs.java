package com.livingdocs.document.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(FIELD)
@Retention(RUNTIME)
@Constraint(validatedBy = UniqueEvidenceRefsValidator.class)
public @interface UniqueEvidenceRefs {
    String message() default "Evidence references must be unique.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
