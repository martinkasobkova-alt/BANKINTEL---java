package cz.bankintel.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordPolicyTest {

    @Test
    void acceptsStrongPassword() {
        assertNull(PasswordPolicy.strengthError("Abcd1234!"));
    }

    @ParameterizedTest
    @CsvSource({
        "short1!,8 znak",
        "abcdefgh,číslic",
        "12345678!,písmeno",
        "Abcdefgh!,číslic",
        "Abcdef12,speciální"
    })
    void rejectsWeakPasswords(String password, String fragment) {
        String err = PasswordPolicy.strengthError(password);
        assertNotNull(err);
        assertTrue(err.toLowerCase().contains(fragment.toLowerCase()), err);
    }

    @Test
    void rejectsNullPassword() {
        assertNotNull(PasswordPolicy.strengthError(null));
    }
}
