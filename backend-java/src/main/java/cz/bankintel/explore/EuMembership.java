package cz.bankintel.explore;

import java.util.Set;

/**
 * The canonical 27 EU member states (ISO 3166-1 alpha-2), matching Eurostat's own country
 * codes and the legacy Python refresh script's {@code EU27} constant exactly (Greece as
 * {@code GR}, not the older {@code EL}).
 *
 * <p>Deliberately separate from {@link ExploreGeoCatalog}'s {@code country_groups}/{@code
 * european_iso2} - that JSON-driven list is a broader ~48-country "is this in Europe"
 * detection set for the UI geo picker (includes non-EU countries like CH/NO/GB/RS/UA), a
 * different purpose and lifecycle than a batch job's fixed EU-membership constant.
 */
public final class EuMembership {

    public static final Set<String> ISO2_CODES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
            "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    private EuMembership() {}
}
