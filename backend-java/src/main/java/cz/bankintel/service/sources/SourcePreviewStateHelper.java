package cz.bankintel.service.sources;

import cz.bankintel.domain.entity.SourceEntity;
import java.time.Instant;

final class SourcePreviewStateHelper {

    private SourcePreviewStateHelper() {}

    static String previewState(SourceEntity source, boolean hasDataset, long rowCount) {
        String syncState = blankToLower(source.getSyncState());
        if (!syncState.isEmpty()) {
            if ("synced".equals(syncState) && rowCount <= 0) {
                return "synced_empty";
            }
            return syncState;
        }
        if ("running".equals(blankToLower(source.getLastSyncStatus()))) {
            return "running";
        }
        if ("pending".equals(blankToLower(source.getSyncQueueState()))) {
            return "rate_limited";
        }
        if (!hasDataset && source.getLastSyncAt() == null) {
            return "not_synced";
        }
        if ("rate_limited".equals(blankToLower(source.getLastSyncReasonCode()))) {
            return "rate_limited";
        }
        String lastStatus = blankToLower(source.getLastSyncStatus());
        if ("error".equals(lastStatus) || "timeout".equals(lastStatus)) {
            return "sync_failed";
        }
        if (rowCount <= 0 && hasDataset) {
            return "synced_empty";
        }
        return "synced";
    }

    static String previewMessage(SourceEntity source, String previewState) {
        String retryAt = formatInstant(source.getSyncRetryAt());
        String lastMsg = source.getLastSyncMessage() != null ? source.getLastSyncMessage().trim() : "";
        return switch (previewState) {
            case "running" -> "Synchronizace zdroje právě probíhá. Zkuste náhled za chvíli znovu.";
            case "rate_limited" -> {
                String suffix = retryAt.isEmpty() ? "" : " Další pokus je naplánovaný na " + retryAt + ".";
                yield ("OECD API dočasně omezuje počet dotazů. Data zatím nebyla stažena. "
                                + "Zkuste synchronizaci později nebo ji nechte doběhnout z fronty."
                                + suffix)
                        .trim();
            }
            case "not_synced" -> "Tato řada ještě nebyla synchronizována. Spusťte synchronizaci.";
            case "sync_failed" -> lastMsg.isEmpty() ? "Synchronizace tohoto zdroje selhala. Zkuste ji prosím znovu." : lastMsg;
            case "synced_empty" -> "Synchronizace proběhla, ale ve zdroji nejsou dostupné žádné hodnoty.";
            default -> "Pro tento zdroj zatím nejsou uložená data.";
        };
    }

    static MapPreviewSource toPreviewSource(SourceEntity source) {
        return new MapPreviewSource(
                source.getId(),
                source.getName(),
                source.getSourceType(),
                source.getDatasetName(),
                source.getLastSyncMessage() != null ? source.getLastSyncMessage() : "");
    }

    record MapPreviewSource(String id, String name, String sourceType, String datasetName, String lastSyncMessage) {}

    private static String blankToLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String formatInstant(Instant instant) {
        return instant != null ? instant.toString() : "";
    }
}
