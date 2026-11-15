package io.cloudforge.identity.api.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

@Getter
public class UpdateMyProfileRequest {

    private String firstName;
    private String lastName;

    @JsonSetter("firstName")
    public void setFirstName(JsonNode value) {
        this.firstName = readName(value);
    }

    @JsonSetter("lastName")
    public void setLastName(JsonNode value) {
        this.lastName = readName(value);
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, JsonNode value) {
        throw new IllegalArgumentException("Unsupported profile field.");
    }

    private static String readName(JsonNode value) {
        // Missing fields do not call a setter.
        // Present fields must be JSON strings, including when their value is null.
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "Profile names must be JSON strings."
            );
        }

        return value.textValue();
    }
}