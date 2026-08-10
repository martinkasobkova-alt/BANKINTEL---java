package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiWebSearchResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parse_extractsTextAndSources() {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "resp_1");
        ArrayNode output = root.putArray("output");

        ObjectNode message = output.addObject();
        message.put("type", "message");
        ArrayNode content = message.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "output_text");
        textNode.put("text", "- Stabilní vláda\n- Verdikt: vhodné");
        ArrayNode annotations = textNode.putArray("annotations");
        ObjectNode ann = annotations.addObject();
        ann.put("url", "https://example.com/politics");
        ann.put("title", "Politics brief");

        ObjectNode searchCall = output.addObject();
        searchCall.put("type", "web_search_call");
        ObjectNode action = searchCall.putObject("action");
        ArrayNode sources = action.putArray("sources");
        ObjectNode src = sources.addObject();
        src.put("url", "https://news.example/si");
        src.put("title", "SI news");

        OpenAiWebSearchResponseParser.Parsed parsed = OpenAiWebSearchResponseParser.parse(root);
        assertEquals("resp_1", parsed.responseId());
        assertTrue(parsed.text().contains("Stabilní vláda"));
        assertEquals(2, parsed.sources().size());

        List<Map<String, Object>> maps = OpenAiWebSearchResponseParser.sourceUrlMaps(parsed.sources(), 1);
        assertEquals(1, maps.size());
        assertEquals("https://example.com/politics", maps.get(0).get("url"));
    }
}
