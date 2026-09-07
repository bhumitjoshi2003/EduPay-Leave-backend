-- Phase 1A of the academic-session consolidation: AcademicSession is becoming the
-- authoritative "what session is this school currently in" answer, so the invariant
-- AcademicSessionService.setCurrentSession/createSession already maintain by hand
-- (unset the existing current row, then set the new one, inside one @Transactional
-- method) should also be guaranteed by the database, not just by careful application
-- code always going through that one service.
--
-- Verified against the target database before writing this migration:
--   SELECT school_id, COUNT(*) FROM academic_session WHERE is_current = TRUE
--   GROUP BY school_id HAVING COUNT(*) > 1;
-- returned zero rows — no existing school currently violates this, so the index
-- below is safe to add unconditionally. A school with zero current sessions (also
-- confirmed to exist in this data) is unaffected, since a partial index only
-- constrains the rows where is_current = TRUE.
CREATE UNIQUE INDEX IF NOT EXISTS uq_academic_session_one_current_per_school
    ON academic_session (school_id)
    WHERE is_current = TRUE;
