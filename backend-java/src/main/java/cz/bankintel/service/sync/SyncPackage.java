package cz.bankintel.service.sync;

/**
 * Synchronizace datových zdrojů — Java port {@code backend/services/sync_service.py}.
 *
 * <p>Tok: načti {@link cz.bankintel.domain.entity.SourceEntity} → zavolej konektor → parse →
 * upsert do {@code records} → aktualizuj stav zdroje a {@code sync_logs}.
 *
 * <ul>
 *   <li>{@link SyncService} — hlavní engine ({@code runSync}, async fronta)
 *   <li>{@link SyncQueryService} — dotazy na historii synců
 *   <li>{@link RecordKeyUtil} — dedupe klíč řádku (jako Python {@code _record_key})
 * </ul>
 *
 * <p>REST: {@link cz.bankintel.controller.sources.SourcesController}
 * — {@code POST /api/sources/{id}/sync}, {@code /sync-public}
 */
public final class SyncPackage {

    private SyncPackage() {}
}
