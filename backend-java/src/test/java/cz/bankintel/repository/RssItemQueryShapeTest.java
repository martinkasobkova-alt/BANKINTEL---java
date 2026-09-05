package cz.bankintel.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Dotaz na RSS položky nesmí testovat parametry na NULL.
 *
 * Kontext: `:param IS NULL` shodí na PostgreSQL celý dotaz, protože databáze neumí odvodit typ
 * nenaplněného parametru. Protože `category` i `search` byly při běžném volání null, padalo
 * `/api/rss/items` vždycky a výpis RSS položek nefungoval nikomu. Místo null se posílá prázdný
 * řetězec a EPOCH; tenhle test hlídá, aby se ta podmínka nevrátila.
 */
class RssItemQueryShapeTest {

    private static String findFilteredQuery() throws NoSuchMethodException {
        Method method = RssItemRepository.class.getMethod(
                "findFiltered",
                java.util.List.class,
                String.class,
                java.time.Instant.class,
                String.class,
                org.springframework.data.domain.Pageable.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("findFiltered musí mít @Query").isNotNull();
        return query.value();
    }

    @Test
    void dotazNetestujeParametryNaNull() throws Exception {
        String jpql = findFilteredQuery();
        assertThat(jpql)
                .as("podmínka `:parametr IS NULL` shodí dotaz na PostgreSQL")
                .doesNotContain(":category IS NULL")
                .doesNotContain(":cutoff IS NULL")
                .doesNotContain(":search IS NULL");
    }

    @Test
    void prazdnyRetezecZastupujeVypnutyFiltr() throws Exception {
        String jpql = findFilteredQuery();
        assertThat(jpql).contains(":category = ''").contains(":search = ''");
    }
}
