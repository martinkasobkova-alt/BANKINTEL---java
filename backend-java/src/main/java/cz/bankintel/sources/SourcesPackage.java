package cz.bankintel.sources;

/**
 * Katalogové browsery — prohlížení stromů datasetů v UI (bez stažení celé řady).
 *
 * <p>Každý podbalíček odpovídá Python souboru {@code *_catalog_routes.py}. Controller je v
 * {@code cz.bankintel.controller.sources.*CatalogController}.
 *
 * <table>
 *   <tr><th>Balíček</th><th>API prefix</th><th>Python originál</th></tr>
 *   <tr><td>{@code sources.arad}</td><td>{@code /api/arad}</td><td>{@code arad_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.fred}</td><td>{@code /api/fred}</td><td>{@code fred_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.eurostat}</td><td>{@code /api/eurostat/catalog}</td><td>{@code eurostat_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.csu}</td><td>{@code /api/csu}</td><td>{@code csu_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.bis}</td><td>{@code /api/bis}</td><td>{@code bis_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.imf}</td><td>{@code /api/imf/catalog}</td><td>{@code imf_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.oecd}</td><td>{@code /api/oecd}</td><td>{@code oecd_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.data360}</td><td>{@code /api/data360}</td><td>{@code data360_catalog_routes.py}</td></tr>
 *   <tr><td>{@code sources.ecb}</td><td>{@code /api/ecb}, {@code /api/ecb2}</td><td>{@code ecb_catalog_routes.py}, {@code ecb2_catalog_routes.py}</td></tr>
 * </table>
 *
 * <p>Pro live data (graf/náhled) slouží {@link cz.bankintel.connector.ConnectorFactory}, ne tyto
 * catalog služby.
 */
public final class SourcesPackage {

    private SourcesPackage() {}
}
