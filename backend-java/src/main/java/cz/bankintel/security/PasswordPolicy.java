package cz.bankintel.security;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Společná pravidla síly hesla — registrace, admin vytvoření uživatele, změna hesla. */
@Component
public class PasswordPolicy {

    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SPECIAL = Pattern.compile("[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]");

    public void validateOrThrow(String password) {
        String message = strengthError(password);
        if (message != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    static String strengthError(String password) {
        if (password == null) {
            return "Heslo je povinné.";
        }
        if (password.length() < 8) {
            return "Heslo musí mít alespoň 8 znaků.";
        }
        if (!LETTER.matcher(password).find()) {
            return "Heslo musí obsahovat alespoň jedno písmeno.";
        }
        if (!DIGIT.matcher(password).find()) {
            return "Heslo musí obsahovat alespoň jednu číslici.";
        }
        if (!SPECIAL.matcher(password).find()) {
            return "Heslo musí obsahovat alespoň jeden speciální znak (např. !@#$%).";
        }
        return null;
    }
}
