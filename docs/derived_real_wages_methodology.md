# Derived Real Wages Methodology

Direct real-wage series are not assumed to exist in the raw catalog or Search V2 sidecar. The application now treats real wages as a derived-series calculation, not as a query-specific ranking rule.

## Planner Intent

A real-wages request should be planned as:

- nominal concept: wages
- transformation: inflation_adjusted / real
- geo: requested country, for example `CZ`
- compatible frequency: nominal wages and CPI/HICP must share the same frequency

The search layer may retrieve candidate nominal wage and consumer-price series, but the calculation belongs to the analytics/derived layer.

## Inputs

Required input A:

- nominal whole-economy wage series
- not government-only wages
- not compensation of employees as a national-accounts flow unless explicitly requested
- not a subgroup, occupation, industry, or labour-cost proxy for a whole-economy query

Required input B:

- CPI/HICP consumer-price index or inflation-rate series
- same geography
- same frequency
- overlapping periods

## Supported Formulas

Index variant:

```text
real_wage_index_t = nominal_wage_index_t / consumer_price_index_t * base_value
```

Growth variant:

```text
real_growth_t = (1 + nominal_growth_t) / (1 + inflation_rate_t) - 1
```

The simple difference `nominal_growth - inflation` is exposed only as an approximation where useful. It must not be presented as the exact result.

## Output Contract

The derived engine returns:

```json
{
  "result_type": "derived_series",
  "concept": "real_wages",
  "formula": "...",
  "input_series": [],
  "geo": "CZ",
  "frequency": "...",
  "unit": "...",
  "methodology_note": "Přímá řada reálných mezd nebyla v katalogu dostupná. Aplikace výsledek vypočítala z nominálních mezd a spotřebitelských cen.",
  "warnings": []
}
```

## Guardrails

The engine rejects:

- incompatible geo
- incompatible frequency
- no common periods
- empty or invalid input values
- government-sector wages for a whole-economy real-wage request
- compensation-of-employees or labour-cost proxies when the request is for average wages
- already-real nominal input
- unrecognized consumer-price input

## Implementation

- `backend-java/src/main/java/cz/bankintel/service/timeseries/DerivedRealWagesService.java`
- `backend-java/src/main/java/cz/bankintel/service/timeseries/RealValuesAnalyticsService.java`

`RealValuesAnalyticsService` now computes real YoY growth with the exact compounding formula and separately exposes the subtraction approximation as `approx_real_yoy_pct`.

## Tests

Covered by:

- `DerivedRealWagesServiceTest`
- `RealValuesAnalyticsServiceTest`

These tests verify index calculation, exact growth compounding, geo/frequency rejection, government-wage rejection, and the `derived_series` result marker.
