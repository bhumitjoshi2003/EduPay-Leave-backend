-- Pickup Authorization is not a real workflow in this product and has no consumer outside
-- the Parent Portal module (confirmed: only V36's creation, the ParentStudentRelationship
-- entity, and ParentPortalService/ParentPortalController referenced this column). Safe to drop.
ALTER TABLE parent_student_relationship DROP COLUMN pickup_authorized;
