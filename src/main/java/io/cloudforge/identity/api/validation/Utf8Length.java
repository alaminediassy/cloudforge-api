package io.cloudforge.identity.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8LengthValidator.class)
public @interface Utf8Length {
    String message() default "Must not exceed {max} UTF-8 bytes";
    int max();
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
