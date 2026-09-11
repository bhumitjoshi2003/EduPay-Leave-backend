-- Gita for Life has been removed from the product. Thought of the Day (wisdom_thought,
-- wisdom_override) is unaffected and stays exactly as V59/V60 created it.
--
-- wisdom_teaching is dropped first because it holds a foreign key to wisdom_verse.
-- Both tables were empty in every known environment at the time of this migration:
-- the verified-verse corpus import was never run against a persistent database, and no
-- school ever had the WISDOM feature enabled, so no teaching could have been authored.
DROP TABLE IF EXISTS wisdom_teaching;
DROP TABLE IF EXISTS wisdom_verse;
