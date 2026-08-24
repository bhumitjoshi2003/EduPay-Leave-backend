-- StudentTransportFeeAssignment.distance is mapped as java.lang.Double. Hibernate 6
-- therefore validates the PostgreSQL column as float(53) / double precision. V22
-- originally created it as NUMERIC(10,2), which prevents the application from starting
-- when ddl-auto=validate is enabled.
--
-- This conversion is safe both after the original V22 definition and when an environment
-- has already been corrected manually: PostgreSQL accepts the same type conversion again.
ALTER TABLE student_transport_fee_assignment
    ALTER COLUMN distance TYPE DOUBLE PRECISION
    USING distance::double precision;
