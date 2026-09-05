package cz.bankintel.service.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.entity.FeatureAccessRuleEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.FeatureAccessRuleRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pravidla pilotního spuštění (migrace V14): nepřihlášený data najde a zobrazí si je,
 * registrovaný s nimi dál pracuje, placené zůstávají jen reklamy.
 */
@ExtendWith(MockitoExtension.class)
class FeatureAccessPilotTest {

    @Mock private FeatureAccessRuleRepository repository;

    private FeatureAccessService service() {
        return new FeatureAccessService(repository);
    }

    private void rule(String key, String level) {
        FeatureAccessRuleEntity e = new FeatureAccessRuleEntity();
        e.setFeatureKey(key);
        e.setLabel(key);
        e.setAccessLevel(level);
        when(repository.findById(key)).thenReturn(Optional.of(e));
    }

    private UserEntity registered() {
        UserEntity u = new UserEntity();
        u.setId("u1");
        u.setRole("user");
        return u;
    }

    @Test
    void anonymNajdeDataAZobraziSiJe() {
        rule("catalog_deep_search", "public");
        assertTrue(service().canAccessFeature(null, "catalog_deep_search"));
    }

    @Test
    void anonymNesmiOdnestDataVen() {
        rule("export_data", "registered");
        assertFalse(service().canAccessFeature(null, "export_data"));
    }

    @Test
    void registrovanyDataExportovatMuze() {
        rule("export_data", "registered");
        assertTrue(service().canAccessFeature(registered(), "export_data"));
    }

    @Test
    void managerExplorerAAiNadGrafemJsouAzPoRegistraci() {
        rule("manager_explorer", "registered");
        rule("chart_ai", "registered");
        FeatureAccessService s = service();
        assertFalse(s.canAccessFeature(null, "manager_explorer"));
        assertFalse(s.canAccessFeature(null, "chart_ai"));
        assertTrue(s.canAccessFeature(registered(), "manager_explorer"));
        assertTrue(s.canAccessFeature(registered(), "chart_ai"));
    }

    @Test
    void bezReklamZustavaPlacene() {
        rule("ad_free_dashboard", "subscriber");
        assertFalse(service().canAccessFeature(registered(), "ad_free_dashboard"));
    }

    @Test
    void hlaskaUPraceSDatyRikaProcSeRegistrovat() {
        rule("export_data", "registered");
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service().requireFeature(null, "export_data"));
        assertTrue(String.valueOf(ex.getReason()).contains("zaregistrujte se"));
    }

    @Test
    void hlaskaUOstatnichZustavaObecna() {
        rule("manager_explorer", "registered");
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service().requireFeature(null, "manager_explorer"));
        assertEquals("Tato funkce je dostupná po přihlášení.", ex.getReason());
    }
}
