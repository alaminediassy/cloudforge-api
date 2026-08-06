package io.cloudforge.identity.domain;

import lombok.Getter;

@Getter
public enum UserStatus {
    PENDING("pending"),
    ACTIVE("active"),
    SUSPENDED("suspended");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    public static UserStatus fromValue(String value) {
        for (UserStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown user status: " + value);
    }
}
