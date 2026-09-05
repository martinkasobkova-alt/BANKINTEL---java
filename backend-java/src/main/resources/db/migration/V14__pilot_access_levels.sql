-- Pilotní spuštění: dvě úrovně místo tří.
--
-- Pravidlo: nepřihlášený data NAJDE a ZOBRAZÍ si je, registrovaný s nimi dál pracuje
-- (odnese je ven nebo si je uloží). Placené nezůstává nic kromě reklam.
--
-- Důvod: katalogové vyhledávání jede přes AI a bylo na 'registered', takže nepřihlášený
-- návštěvník neměl jak řadu vůbec najít.

-- 1) Najít, zobrazit, skládat — bez přihlášení.
--    composite_charts sem patří proto, že skládání řad je práce V aplikaci,
--    ne odnášení dat ven.
UPDATE feature_access_rules SET access_level = 'public'
 WHERE feature_key IN (
    'catalog_deep_search',
    'chart_type',
    'chart_period',
    'chart_time_range',
    'chart_table_toggle',
    'composite_charts'
 );

-- 2) Odnést data ven nebo si je uložit — až po registraci (dřív předplatné).
UPDATE feature_access_rules SET access_level = 'registered'
 WHERE feature_key IN (
    'export_data',
    'chart_image_export',
    'save_widget',
    'personal_dashboard',
    'multiple_dashboards',
    'saved_calculations',
    'upload_custom_data',
    'company_data_analysis',
    'rss_monitoring'
 );

-- 3) ad_free_dashboard zůstává 'subscriber' — jediná placená věc.
--    Anonym i registrovaný vidí inzerci, předplatitel ne.

-- 4) Dva gaty, které dosud neexistovaly vůbec (Manager Explorer a AI nad grafem
--    byly otevřené všem). Zavádí se rovnou na 'registered'.
INSERT INTO feature_access_rules (feature_key, label, description, access_level) VALUES
('manager_explorer', 'Manager Explorer', 'Procházení zdrojů, datových sad a řad s AI analýzou.', 'registered'),
('chart_ai', 'AI nad grafem', 'Vysvětlení řady, navazující dotazy a související řady.', 'registered')
ON CONFLICT (feature_key) DO NOTHING;
