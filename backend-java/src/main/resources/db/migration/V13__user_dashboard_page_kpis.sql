-- KPI dlaždice na osobním dashboardu.
--
-- Dosud se headline_kpis držely jen u veřejného přehledu (homepage_config) a u sekcí
-- (sections) — obojí spravuje admin. Osobní stránky žádné neměly, takže si uživatel
-- nemohl dát klíčová čísla nad své widgety. Sloupec je stejného tvaru jako tam,
-- aby ho uměla obsloužit stávající komponenta HeadlineKpiStrip.
ALTER TABLE user_dashboard_pages
    ADD COLUMN IF NOT EXISTS headline_kpis JSONB NOT NULL DEFAULT '[]'::jsonb;
