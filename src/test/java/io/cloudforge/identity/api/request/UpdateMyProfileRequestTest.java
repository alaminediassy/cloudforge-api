package io.cloudforge.identity.api.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@JsonTest
@ActiveProfiles("test")
class UpdateMyProfileRequestTest {

    @Autowired
    private ObjectMapper json;

    @Test
    void readsBothNames() throws Exception {
        var request = read("""
                {
                    "firstName": "Adama",
                    "lastName": "Ndiaye"
                }
                """);

        assertEquals("Adama", request.getFirstName());
        assertEquals("Ndiaye", request.getLastName());
    }

    @Test
    void keepsMissingLastNameAbsent() throws Exception {
        var request = read("""
                {"firstName": "Élodie"}
                """);

        assertEquals("Élodie", request.getFirstName());
        assertNull(request.getLastName());
    }

    @Test
    void keepsMissingFirstNameAbsent() throws Exception {
        var request = read("""
                {"lastName": "Martin"}
                """);

        assertNull(request.getFirstName());
        assertEquals("Martin", request.getLastName());
    }

    @Test
    void leavesEmptyUpdateForUseCaseValidation() throws Exception {
        var request = read("{}");

        assertNull(request.getFirstName());
        assertNull(request.getLastName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"firstName\":null}",
            "{\"lastName\":null}",
            "{\"firstName\":\"Adama\",\"lastName\":null}",
            "{\"firstName\":null,\"lastName\":\"Ndiaye\"}"
    })
    void rejectsExplicitNull(String body) {
        assertThrows(JsonProcessingException.class, () -> read(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123",
            "true",
            "[]",
            "{}"
    })
    void rejectsNonStringNames(String value) {
        assertThrows(
                JsonProcessingException.class,
                () -> read("{\"firstName\":" + value + "}")
        );
        assertThrows(
                JsonProcessingException.class,
                () -> read("{\"lastName\":" + value + "}")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "email",
            "password",
            "passwordHash",
            "status",
            "id",
            "unknownField"
    })
    void rejectsUnsupportedFields(String fieldName) {
        String body = """
                {"firstName":"Adama","%s":"unexpected"}
                """.formatted(fieldName);

        assertThrows(JsonProcessingException.class, () -> read(body));
    }

    @Test
    void preservesTextForDomainValidation() throws Exception {
        var request = read("""
                {"firstName":"  Adama  ","lastName":""}
                """);

        assertEquals("  Adama  ", request.getFirstName());
        assertEquals("", request.getLastName());
    }

    private UpdateMyProfileRequest read(String body)
            throws JsonProcessingException {
        return json.readValue(body, UpdateMyProfileRequest.class);
    }
}