package cz.bankintel.controller;

/**
 * REST API vrstva — mapuje HTTP endpointy na služby.
 *
 * <h2>Hlavní moduly</h2>
 * <ul>
 *   <li>{@code controller.auth} — přihlášení, JWT cookies
 *   <li>{@code controller.catalog} — vyhledávání a náhled katalogu ({@code /api/catalog/*})
 *   <li>{@code controller.sources} — CRUD zdrojů + katalogové browsery ({@code /api/arad}, {@code /api/fred}, …)
 *   <li>{@code controller.explore} — mapový průzkumník ({@code /api/explore/*})
 *   <li>{@code controller.homepage} — konfigurace a render homepage
 *   <li>{@code controller.me} — osobní dashboard uživatele
 *   <li>{@code controller.chat} — uživatelský chat ({@code /api/chat/*})
 *   <li>{@code controller.admin} — správa uživatelů, zdrojů, článků
 *   <li>{@code controller.stub} — dočasné stub odpovědi (co ještě není portováno z Pythonu)
 * </ul>
 *
 * <p>Detailní mapa: {@code docs/CODE_MAP.md}
 */
public final class ControllerPackage {

    private ControllerPackage() {}
}
