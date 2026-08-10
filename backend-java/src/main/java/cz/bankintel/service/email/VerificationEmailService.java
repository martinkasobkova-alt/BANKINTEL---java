package cz.bankintel.service.email;

public interface VerificationEmailService {

    void sendVerificationEmail(String email, String rawToken);

    void sendPasswordResetEmail(String email, String rawToken);
}
