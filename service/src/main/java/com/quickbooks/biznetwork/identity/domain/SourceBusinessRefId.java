package com.quickbooks.biznetwork.identity.domain;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SourceBusinessRefId implements Serializable {
    private String sourceSystem;

    // Without this, Hibernate defaults enum mapping to ORDINAL (an integer
    // column) instead of the VARCHAR the schema actually declares -- caught
    // at container startup via ddl-auto: validate, not at compile time,
    // which is exactly why this slipped through javac/unit-test checks.
    @Enumerated(EnumType.STRING)
    private SourceEntityType sourceEntityType;

    private String sourceEntityId;
}
