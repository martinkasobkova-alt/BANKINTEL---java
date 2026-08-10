package cz.bankintel.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bankintel")
public record BankIntelProperties(
        Jwt jwt,
        Cors cors,
        Cookie cookie,
        Dev dev,
        String subscriberRegistrationCode,
        Catalog catalog,
        Chat chat,
        Storage storage) {

    public record Catalog(String indexDir, String ftsDb, String metadataDir) {}

    public record Chat(String attachmentDir) {}

    public record Storage(String userPrivateDir, String adminUploadsDir, String podcastMediaDir) {}

    public record Jwt(String secret, int accessTtlMinutes, int refreshTtlDays) {}

    public record Cors(String origins) {
        public List<String> originList() {
            if (origins == null || origins.isBlank()) {
                return List.of();
            }
            return Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    public record Cookie(boolean secure, String sameSite, String domain) {}

    public record Dev(boolean seed, boolean logVerificationTokens) {}
}
