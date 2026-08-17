-- V16__school_timezone.sql
-- All date/time decisions for teacher attendance (what day is "today", when is a check-in
-- "late", when has a working day fully elapsed) were previously computed using the
-- application server's JVM default timezone rather than the school's own — silently wrong for
-- any school not in the server's timezone, and fragile even for schools that happen to match it
-- today (a server redeploy to a different region would silently shift every school's attendance
-- calculations). This column makes the school's timezone an explicit, persisted fact.
--
-- IANA zone id (e.g. 'Asia/Kolkata'), not a fixed UTC offset — so it stays correct across DST
-- transitions in zones that observe it. Defaults to 'Asia/Kolkata' to match this codebase's
-- existing India-only assumptions elsewhere (en-IN locale, INR currency).

ALTER TABLE public.school
    ADD COLUMN timezone character varying(64) NOT NULL DEFAULT 'Asia/Kolkata';
