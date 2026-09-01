-- Additive only. NULL means either (a) the teacher's class-teacher class has no configured
-- sections, so no section is needed, or (b) a pre-existing ("legacy") class-teacher assignment
-- to a class that DOES have sections, left deliberately unresolved rather than guessed — see
-- TeacherClassScopeService, which blocks combined-section access for case (b) until an admin
-- explicitly picks a section. No existing data is modified; every current teacher row is
-- unaffected until an admin acts on it.
ALTER TABLE teacher ADD COLUMN class_teacher_section_id BIGINT NULL;
