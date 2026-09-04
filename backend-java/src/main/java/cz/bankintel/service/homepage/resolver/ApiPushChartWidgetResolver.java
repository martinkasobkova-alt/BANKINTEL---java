package cz.bankintel.service.homepage.resolver;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolver for {@code api_push_chart} widgets (data pushed via {@code /api/connect/v1}). There is
 * nothing to live-fetch — an external system pushes updates whenever it has new data — so this only
 * runs when {@link cz.bankintel.service.me.PersonalWidgetRenderService} calls
 * {@code WidgetRenderService.resolve} directly: a force-refresh, or a preview before any data has ever
 * been pushed. The normal dashboard view serves the stored {@code data_snapshot} straight from
 * {@code PersonalWidgetRenderService.renderWithSnapshot}, which never reaches this class at all.
 */
@Component
public class ApiPushChartWidgetResolver {

    public Map<String, Object> resolve() {
        return Map.of("info", "Data se aktualizují přes API push. Zatím nebyla přijata žádná data.");
    }
}
