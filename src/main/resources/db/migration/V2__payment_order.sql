-- Server-side record of a Razorpay order at creation time. Closes a real fraud gap:
-- verifyPayment previously trusted a client-supplied "orderDetails" map for the studentId,
-- amount, and months being paid — a valid Razorpay signature only proves a payment was
-- captured for a given orderId, it proves nothing about which student/amount that order
-- was created for. This table is what verifyPayment now looks up instead.
--
-- Unlike the older legacy fee tables (payment, student_fees, fee_structure, bus_fees),
-- which predate Flyway management and exist purely via Hibernate ddl-auto, this new table
-- is properly migration-tracked from the start.

CREATE TABLE payment_order (
    id               BIGSERIAL PRIMARY KEY,
    order_id         VARCHAR(64) NOT NULL UNIQUE,
    school_id        BIGINT NOT NULL,
    student_id       VARCHAR(50) NOT NULL,
    class_name       VARCHAR(100) NOT NULL,
    session          VARCHAR(16) NOT NULL,
    month            VARCHAR(12) NOT NULL,
    amount           INT NOT NULL,
    bus_fee          INT NOT NULL DEFAULT 0,
    tuition_fee      INT NOT NULL DEFAULT 0,
    annual_charges   INT NOT NULL DEFAULT 0,
    lab_charges      INT NOT NULL DEFAULT 0,
    eca_project      INT NOT NULL DEFAULT 0,
    examination_fee  INT NOT NULL DEFAULT 0,
    additional_charges INT NOT NULL DEFAULT 0,
    late_fees        INT NOT NULL DEFAULT 0,
    platform_fee     INT NOT NULL DEFAULT 0,
    consumed         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_order_student ON payment_order(student_id, school_id);
