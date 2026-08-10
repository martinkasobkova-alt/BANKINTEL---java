package cz.bankintel.util;

import java.util.regex.Pattern;

public final class SlugUtils {

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern MULTI_DASH = Pattern.compile("-{2,}");

    private SlugUtils() {}

    public static String slugify(String value, String fallback) {
        String s = (value == null ? "" : value).trim().toLowerCase();
        s = NON_SLUG.matcher(s).replaceAll("-");
        s = MULTI_DASH.matcher(s).replaceAll("-");
        s = s.replaceAll("^-|-$", "");
        if (s.isEmpty()) {
            return fallback;
        }
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
