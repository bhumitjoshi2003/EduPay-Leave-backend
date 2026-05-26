-- ============================================================================
-- V2: Fee System Architecture C — Hybrid Rule-Based with Invoice Materialization
-- ============================================================================
-- This migration creates the new fee management tables alongside the existing ones.
-- The old tables (fee_structure, student_fees, payment, bus_fees) remain untouched
-- and can be deprecated once migration is complete.
-- ============================================================================

-- 1. Academic Session (replaces string-based "2025-2026" session tracking)
CREATE TABLE IF NOT EXISTS academic_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    label VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_session_school_label UNIQUE (school_id, label),
    INDEX idx_as_school (school_id)
);

-- 2. Fee Head (configurable fee types per school)
CREATE TABLE IF NOT EXISTS fee_head (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(30) NOT NULL,
    frequency VARCHAR(20) NOT NULL,
    due_months TEXT NOT NULL,
    is_optional BOOLEAN NOT NULL DEFAULT FALSE,
    is_refundable BOOLEAN NOT NULL DEFAULT FALSE,
    sibling_discount_pct DECIMAL(5,2),
    display_order INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_fee_head_school_code UNIQUE (school_id, code),
    INDEX idx_fh_school_active (school_id, active)
);

-- 3. Fee Structure Rule (amount per fee head per class per session)
CREATE TABLE IF NOT EXISTS fee_structure_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    fee_head_id BIGINT NOT NULL,
    academic_session_id BIGINT NOT NULL,
    class_name VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    effective_from DATE NOT NULL,
    effective_until DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_fsr_fee_head FOREIGN KEY (fee_head_id) REFERENCES fee_head(id),
    CONSTRAINT fk_fsr_session FOREIGN KEY (academic_session_id) REFERENCES academic_session(id),
    INDEX idx_fsr_school_session_class (school_id, academic_session_id, class_name)
);

-- 4. Student Fee Config (per-student overrides: discounts, waivers, opt-outs)
CREATE TABLE IF NOT EXISTS student_fee_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    student_id VARCHAR(50) NOT NULL,
    fee_head_id BIGINT NOT NULL,
    academic_session_id BIGINT NOT NULL,
    config_type VARCHAR(20) NOT NULL,
    value DECIMAL(15,2),
    reason VARCHAR(500),
    approved_by VARCHAR(100),
    valid_from DATE,
    valid_until DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sfc_fee_head FOREIGN KEY (fee_head_id) REFERENCES fee_head(id),
    CONSTRAINT fk_sfc_session FOREIGN KEY (academic_session_id) REFERENCES academic_session(id),
    INDEX idx_sfc_student_session (school_id, student_id, academic_session_id)
);

-- 5. Invoice (materialized monthly fee record per student)
CREATE TABLE IF NOT EXISTS invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_number VARCHAR(30) NOT NULL,
    school_id BIGINT NOT NULL,
    student_id VARCHAR(50) NOT NULL,
    academic_session_id BIGINT NOT NULL,
    billing_month INT NOT NULL,
    due_date DATE NOT NULL,
    total_amount BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    net_amount BIGINT NOT NULL,
    amount_paid BIGINT NOT NULL DEFAULT 0,
    balance_due BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    issued_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_invoice_number_school UNIQUE (school_id, invoice_number),
    CONSTRAINT fk_inv_session FOREIGN KEY (academic_session_id) REFERENCES academic_session(id),
    INDEX idx_invoice_student_session (school_id, student_id, academic_session_id),
    INDEX idx_invoice_status (school_id, status)
);

-- 6. Invoice Line Item (immutable fee breakdown per invoice)
CREATE TABLE IF NOT EXISTS invoice_line_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    fee_head_id BIGINT NOT NULL,
    fee_head_code VARCHAR(30) NOT NULL,
    description VARCHAR(200),
    base_amount BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    net_amount BIGINT NOT NULL,
    CONSTRAINT fk_ili_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(id) ON DELETE CASCADE,
    CONSTRAINT fk_ili_fee_head FOREIGN KEY (fee_head_id) REFERENCES fee_head(id),
    INDEX idx_ili_invoice (invoice_id)
);

-- 7. Fee Payment (new payment records)
CREATE TABLE IF NOT EXISTS fee_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    student_id VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    payment_mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    razorpay_payment_id VARCHAR(50),
    razorpay_order_id VARCHAR(50),
    razorpay_signature VARCHAR(255),
    reference_number VARCHAR(100),
    notes VARCHAR(500),
    received_by VARCHAR(100),
    payment_date TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fp_student_school (school_id, student_id),
    INDEX idx_fp_razorpay_order (razorpay_order_id)
);

-- 8. Payment Allocation (many-to-many between payments and invoices)
CREATE TABLE IF NOT EXISTS payment_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fee_payment_id BIGINT NOT NULL,
    invoice_id BIGINT NOT NULL,
    amount_allocated BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_invoice UNIQUE (fee_payment_id, invoice_id),
    CONSTRAINT fk_pa_payment FOREIGN KEY (fee_payment_id) REFERENCES fee_payment(id),
    CONSTRAINT fk_pa_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(id),
    INDEX idx_pa_invoice (invoice_id)
);

-- 9. Credit Note (refunds, adjustments, write-offs)
CREATE TABLE IF NOT EXISTS credit_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    student_id VARCHAR(50) NOT NULL,
    invoice_id BIGINT,
    credit_type VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_by VARCHAR(100),
    approved_at TIMESTAMP NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cn_invoice FOREIGN KEY (invoice_id) REFERENCES invoice(id),
    INDEX idx_cn_student_school (school_id, student_id)
);
