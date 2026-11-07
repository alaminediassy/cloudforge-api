package io.cloudforge.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Sha256HashProviderTest {
    @Test void preservesExistingStoredHashFormat() {
        Sha256HashProvider provider = new Sha256HashProvider();
        assertEquals("8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92", provider.hash("123456"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", provider.hash("abc"));
    }
}
