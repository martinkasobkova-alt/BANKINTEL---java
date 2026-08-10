package cz.bankintel.service.timeseries;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Agreguje časovou řadu (period -> value) na hrubší periodicitu (W/M/Q/H/Y) - Java protějšek
 * frontendového {@code aggregateRows} (AradView.jsx), použitý Analytikou/Forecastem, aby
 * respektovaly stejnou periodicitu, jakou má uživatel právě vybranou v grafu (viz
 * CatalogAnalyticsService/CatalogForecastService, parametr {@code target_frequency}).
 */
public final class TimeSeriesResampler {

    private static final Map<String, Integer> FREQ_RANK = Map.of("D", 0, "W", 1, "M", 2, "Q", 3, "H", 4, "Y", 5);

    private TimeSeriesResampler() {}

    public static Map<String, Double> resample(
            Map<String, Double> values, String nativeFrequency, String targetFrequency) {
        String target = normalize(targetFrequency);
        String nativeFreq = normalize(nativeFrequency);
        if (target.isEmpty() || values == null || values.isEmpty()) {
            return values;
        }
        Integer targetRank = FREQ_RANK.get(target);
        Integer nativeRank = FREQ_RANK.get(nativeFreq);
        if (targetRank == null) {
            return values;
        }
        if (nativeRank != null && targetRank <= nativeRank) {
            // Cílová frekvence není hrubší než nativní - jemnější agregaci nelze vyrobit z toho, co
            // už máme, takže vrátíme beze změny.
            return values;
        }

        Map<String, LocalDate> parsedDates = new LinkedHashMap<>();
        for (String period : values.keySet()) {
            LocalDate date = parsePeriod(period);
            if (date != null) {
                parsedDates.put(period, date);
            }
        }
        // Když se nepodařilo naparsovat většinu období (např. už textový formát jako "2024-Q3"
        // nebo české názvy měsíců u ČSÚ), radši vrátíme původní řadu beze změny než rozbitá data.
        if (parsedDates.size() < values.size() * 0.9) {
            return values;
        }

        Map<String, List<LocalDate>> bucketDates = new LinkedHashMap<>();
        for (Map.Entry<String, LocalDate> entry : parsedDates.entrySet()) {
            String bucketKey = bucketKeyFor(entry.getValue(), target);
            bucketDates.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(entry.getValue());
        }

        Map<LocalDate, String> dateToPeriod = new LinkedHashMap<>();
        for (Map.Entry<String, LocalDate> entry : parsedDates.entrySet()) {
            dateToPeriod.put(entry.getValue(), entry.getKey());
        }

        Map<String, Double> out = new TreeMap<>();
        for (Map.Entry<String, List<LocalDate>> bucket : bucketDates.entrySet()) {
            List<LocalDate> dates = bucket.getValue();
            LocalDate lastDate = dates.stream().max(LocalDate::compareTo).orElse(null);
            if (lastDate == null) {
                continue;
            }
            String lastPeriod = dateToPeriod.get(lastDate);
            Double lastValue = values.get(lastPeriod);
            if (lastValue == null) {
                continue;
            }
            // "last" agregace - odpovídá tomu, co uživatel typicky čeká u cenové/úrovňové (ne
            // tokové) řady jako měsíční/týdenní hodnota (např. zavírací cena akcie).
            out.put(bucket.getKey(), lastValue);
        }
        return out;
    }

    private static LocalDate parsePeriod(String period) {
        String p = period == null ? "" : period.trim();
        if (p.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(p);
        } catch (Exception ignored) {
            // spadne na dalsi format nize
        }
        try {
            return YearMonth.parse(p).atDay(1);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String bucketKeyFor(LocalDate date, String target) {
        return switch (target) {
            case "W" -> {
                int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
                int weekYear = date.get(WeekFields.ISO.weekBasedYear());
                yield String.format(Locale.ROOT, "%04d-W%02d", weekYear, week);
            }
            case "M" -> String.format(Locale.ROOT, "%04d-%02d", date.getYear(), date.getMonthValue());
            case "Q" -> String.format(
                    Locale.ROOT, "%04d-Q%d", date.getYear(), date.get(IsoFields.QUARTER_OF_YEAR));
            case "H" -> String.format(Locale.ROOT, "%04d-H%d", date.getYear(), date.getMonthValue() <= 6 ? 1 : 2);
            case "Y" -> String.valueOf(date.getYear());
            default -> date.toString();
        };
    }

    private static String normalize(String freq) {
        return freq == null ? "" : freq.trim().toUpperCase(Locale.ROOT);
    }
}
