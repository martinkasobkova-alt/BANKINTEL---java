package cz.bankintel.service.magazine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MagazineTextChunker {

    private static final int CHUNK_SIZE = 900;
    private static final int CHUNK_MIN = 120;
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n{2,}");

    public List<String> chunkText(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        String[] paragraphs = PARAGRAPH_SPLIT.split(raw);
        List<String> out = new ArrayList<>();
        String cur = "";
        for (String paragraph : paragraphs) {
            String p = paragraph == null ? "" : paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            String candidate = cur.isEmpty() ? p : cur + "\n\n" + p;
            if (candidate.length() <= CHUNK_SIZE) {
                cur = candidate;
                continue;
            }
            if (!cur.isEmpty() && cur.length() >= CHUNK_MIN) {
                out.add(cur);
            }
            cur = "";
            if (p.length() <= CHUNK_SIZE) {
                cur = p;
                continue;
            }
            int i = 0;
            while (i < p.length()) {
                int end = Math.min(i + CHUNK_SIZE, p.length());
                String piece = p.substring(i, end).trim();
                if (!piece.isEmpty()) {
                    out.add(piece);
                }
                i += CHUNK_SIZE;
            }
        }
        if (!cur.isEmpty() && cur.length() >= CHUNK_MIN) {
            out.add(cur);
        }
        return out;
    }
}
