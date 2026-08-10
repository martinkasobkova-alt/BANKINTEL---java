package cz.bankintel.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConnectorFactoryTest {

    @Test
    void normalizesCatalogCommoditiesSourceToPinkSheetConnector() {
        assertEquals("worldbank_pink_sheet", ConnectorFactory.normalizeSourceType("commodities"));
    }
}
