-- Archiv časopisu je předplatitelská věc.
--
-- Do teď na něj nebyl žádný gate a SecurityConfig pouští všechna /api/**, takže si kdokoli
-- stáhl celé PDF čísla bez přihlášení — přitom předplatné časopisu je jediná placená funkce
-- v aplikaci. Nově: číst smí předplatitel, celý soubor nedostane nikdo kromě administrace.
INSERT INTO feature_access_rules (feature_key, label, description, access_level)
VALUES (
    'magazine_archive',
    'Archiv časopisu',
    'Čtení čísel časopisu Bankovnictví, hledání v nich a AI nad jejich obsahem.',
    'subscriber'
)
ON CONFLICT (feature_key) DO UPDATE SET access_level = 'subscriber';
