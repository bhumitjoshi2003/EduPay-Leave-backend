-- V14__teacher_attendance_batch_criterion.sql
-- Records WHICH attendance pattern selected a teacher-initiated reminder batch.
--
-- Until now a batch was implicitly always "students below the percentage threshold", so the
-- threshold column alone described it. Teachers can now also select on recent consecutive
-- absence ("absent the last 3 school days"), which can pick a completely different set of
-- students from the same class on the same day — a student absent three days running may still
-- be above the threshold, and vice versa. Without this column the audit row would say who was
-- emailed but not on what basis.
--
-- Additive and backward compatible: every existing row is, by definition, a threshold batch,
-- which is exactly what the DEFAULT backfills.

ALTER TABLE public.ai_teacher_attendance_reminder_batch
    ADD COLUMN criterion character varying(32) NOT NULL DEFAULT 'BELOW_THRESHOLD';

-- Null for threshold batches — "not applicable", not "zero days".
ALTER TABLE public.ai_teacher_attendance_reminder_batch
    ADD COLUMN min_consecutive_days integer;
