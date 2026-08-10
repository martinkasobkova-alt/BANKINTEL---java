package cz.bankintel.service.chat;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ChatAttachmentPolicy {

    static final long MAX_BYTES = 25L * 1024L * 1024L;

    private static final Pattern SAFE_FILE_RE = Pattern.compile("[^a-zA-Z0-9._ -]+");

    private static final Set<String> ALLOWED_EXT =
            Set.of(
                    ".jpg",
                    ".jpeg",
                    ".png",
                    ".gif",
                    ".webp",
                    ".heic",
                    ".heif",
                    ".pdf",
                    ".doc",
                    ".docx",
                    ".xls",
                    ".xlsx",
                    ".csv",
                    ".txt",
                    ".ppt",
                    ".pptx");

    private ChatAttachmentPolicy() {}

    public static String safeFilename(String name) {
        String cleaned = SAFE_FILE_RE.matcher(name == null ? "" : name.strip()).replaceAll("_");
        cleaned = cleaned.strip().replaceAll("^[._ ]+|[._ ]+$", "");
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, 180);
        }
        return cleaned.isBlank() ? "attachment" : cleaned;
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static boolean isAllowedExtension(String fileName) {
        return ALLOWED_EXT.contains(extension(fileName));
    }
}
