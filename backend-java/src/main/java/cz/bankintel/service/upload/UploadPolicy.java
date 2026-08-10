package cz.bankintel.service.upload;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class UploadPolicy {

    public static final long ME_MAX_BYTES = 8L * 1024L * 1024L;
    public static final long COMPANY_DATA_MAX_BYTES = 15L * 1024L * 1024L;

    private static final Pattern SAFE_FILE_RE = Pattern.compile("[^a-zA-Z0-9._ -]+");

    private static final Set<String> ME_ALLOWED_EXT = Set.of(".csv", ".xlsx", ".xlsm");

    private static final Set<String> COMPANY_DATA_ALLOWED_EXT = Set.of(".csv", ".xlsx", ".xlsm", ".pdf");

    private static final Set<String> COMPANY_DATA_ALLOWED_MIME = Set.of(
            "text/csv",
            "application/csv",
            "text/plain",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf",
            "application/octet-stream");

    private UploadPolicy() {}

    public static String safeFilename(String name) {
        String cleaned = SAFE_FILE_RE.matcher(name == null ? "" : name.strip()).replaceAll("_");
        cleaned = cleaned.strip().replaceAll("^[._ ]+|[._ ]+$", "");
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, 180);
        }
        return cleaned.isBlank() ? "upload" : cleaned;
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static boolean isMeAllowedExtension(String fileName) {
        return ME_ALLOWED_EXT.contains(extension(fileName));
    }

    public static boolean isCompanyDataAllowedExtension(String fileName) {
        return COMPANY_DATA_ALLOWED_EXT.contains(extension(fileName));
    }

    public static boolean isCompanyDataAllowedMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return true;
        }
        return COMPANY_DATA_ALLOWED_MIME.contains(mimeType.strip().toLowerCase(Locale.ROOT));
    }

    public static String relPath(String userId, String uploadId, String ext) {
        String normalized = ext.startsWith(".") ? ext : "." + ext;
        return "user_private/" + userId + "/" + uploadId + normalized;
    }
}
