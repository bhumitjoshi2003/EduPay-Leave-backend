-- Remove live tracking infrastructure
-- Drop tracking-specific tables
DROP TABLE IF EXISTS boarding_event;
DROP TABLE IF EXISTS student_absence_flag;
DROP TABLE IF EXISTS trip;

-- Remove tracking-only columns from transport_stop
ALTER TABLE transport_stop DROP COLUMN IF EXISTS latitude;
ALTER TABLE transport_stop DROP COLUMN IF EXISTS longitude;
ALTER TABLE transport_stop DROP COLUMN IF EXISTS geofence_radius;

-- Remove device binding column from driver
ALTER TABLE driver DROP COLUMN IF EXISTS registered_device_id;
