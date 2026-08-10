package cz.bankintel.service.content;

import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.domain.entity.UserEntity;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MediaUploadService {

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final java.util.Set<String> ALLOWED_IMAGE_TYPES =
            java.util.Set.of("image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml");

    private final FeatureAccessService featureAccessService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    @Value("${CLOUDINARY_URL:}")
    private String cloudinaryUrl;

    public Map<String, Object> upload(UserEntity user, MultipartFile file) {
        featureAccessService.requireFeature(user, "upload_custom_data");
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Nahrajte prosím obrázek ve formátu JPG, PNG, GIF, WebP nebo SVG.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Obrázek je příliš velký. Maximum je 10 MB.");
        }
        return uploadToCloudinary(file.getOriginalFilename() != null ? file.getOriginalFilename() : "image", contentType, content);
    }

    private Map<String, Object> uploadToCloudinary(String fileName, String contentType, byte[] content) {
        CloudinaryConfig config = parseCloudinaryUrl();
        long timestamp = System.currentTimeMillis() / 1000L;
        Map<String, String> signedParams = new TreeMap<>();
        signedParams.put("folder", "bankoapp/rich-text");
        signedParams.put("timestamp", String.valueOf(timestamp));
        String signature = sign(signedParams, config.apiSecret());

        String boundary = "----BankIntelBoundary" + timestamp;
        byte[] body = buildMultipartBody(boundary, fileName, contentType, content, signedParams, config.apiKey(), signature);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cloudinary.com/v1_1/" + config.cloudName() + "/image/upload"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nahrání obrázku do Cloudinary selhalo.");
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> uploaded = mapper.readValue(response.body(), mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", uploaded.getOrDefault("secure_url", uploaded.get("url")));
            out.put("public_id", uploaded.get("public_id"));
            out.put("width", uploaded.get("width"));
            out.put("height", uploaded.get("height"));
            out.put("format", uploaded.get("format"));
            return out;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nahrání obrázku do Cloudinary selhalo.");
        }
    }

    private CloudinaryConfig parseCloudinaryUrl() {
        String raw = cloudinaryUrl != null ? cloudinaryUrl.trim() : "";
        if (raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cloudinary není nakonfigurované.");
        }
        URI uri = URI.create(raw);
        if (!"cloudinary".equals(uri.getScheme()) || uri.getUserInfo() == null || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "CLOUDINARY_URL má neplatný formát.");
        }
        String[] creds = uri.getUserInfo().split(":", 2);
        if (creds.length != 2) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "CLOUDINARY_URL má neplatný formát.");
        }
        return new CloudinaryConfig(uri.getHost(), creds[0], creds[1]);
    }

    private static String sign(Map<String, String> params, String apiSecret) {
        String payload = params.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((payload + apiSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] buildMultipartBody(
            String boundary,
            String fileName,
            String contentType,
            byte[] content,
            Map<String, String> signedParams,
            String apiKey,
            String signature) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : signedParams.entrySet()) {
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n");
            sb.append(entry.getValue()).append("\r\n");
        }
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"api_key\"\r\n\r\n").append(apiKey).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"signature\"\r\n\r\n").append(signature).append("\r\n");
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .append("\"\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n\r\n");
        byte[] prefix = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(content, 0, out, prefix.length, content.length);
        System.arraycopy(suffix, 0, out, prefix.length + content.length, suffix.length);
        return out;
    }

    private record CloudinaryConfig(String cloudName, String apiKey, String apiSecret) {}
}
