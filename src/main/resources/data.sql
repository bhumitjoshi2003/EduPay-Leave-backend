INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2023-2024', 0, 3, 500);
INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2024-2025', 0, 3, 600);
INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2023-2024', 4, 8, 700);
INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2024-2025', 4, 8, 800);
INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2024-2025', 9, NULL, 1000);


INSERT INTO fee_structure (academic_year, class_name, tuition_fee, admission_fee, annual_charges, eca_project, examination_fee, lab_charges)
VALUES
    ('2023-2024', '1', 10000.00, 5000.00, 2000.00, 1000.00, 500.00, 0.00),
    ('2023-2024', '2', 12000.00, 5500.00, 2200.00, 1200.00, 600.00, 0.00),
    ('2023-2024', '3', 14000.00, 6000.00, 2400.00, 1400.00, 700.00, 0.00),
    ('2024-2025', '1', 11000.00, 5500.00, 2500.00, 1200.00, 600.00, 0.00),
    ('2024-2025', '2', 13000.00, 6000.00, 2700.00, 1500.00, 700.00, 0.00),
    ('2024-2025', '3', 15000.00, 6500.00, 2900.00, 1700.00, 800.00, 0.00);



INSERT INTO public.users (user_id, email, password, role) VALUES
('S101', 'vikas.verma@mail.com', 'sitaram', 'STUDENT'),
('S102', 'neha.sharma@mail.com', 'password123', 'STUDENT'),
('S103', 'aisha.singh@mail.com', 'password123', 'STUDENT'),
('S104', 'rahul.kumar@mail.com', 'password123', 'STUDENT'),
('S105', 'samir.iyer@mail.com', 'password123', 'STUDENT');


-- =========================================================================================
-- STUDENT FEE INSERTS FOR ACADEMIC YEAR 2024-2025 (APRIL 2024 TO MARCH 2025)
-- Goal: At least 4 months marked as unpaid (paid = FALSE) per active student.
-- =========================================================================================

-- S101: Vikas Verma (Class 3, Takes Bus. Total Fee: 2300)
-- Status: ACTIVE (4, 7, 11, 2 - UNPAID)
INSERT INTO public.student_fees (student_id, class_name, distance, takes_bus, year, month, amount_paid, manual_payment_received, manually_paid, paid) VALUES
('S101', '3', 5.5, TRUE, '2024-2025', 4, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S101', '3', 5.5, TRUE, '2024-2025', 5, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 6, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 7, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S101', '3', 5.5, TRUE, '2024-2025', 8, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 9, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 10, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 11, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S101', '3', 5.5, TRUE, '2024-2025', 12, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 1, 2300.00, 0.00, FALSE, FALSE),
('S101', '3', 5.5, TRUE, '2024-2025', 2, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S101', '3', 5.5, TRUE, '2024-2025', 3, 2300.00, 0.00, FALSE, FALSE);

-- S102: Neha Sharma (Class 2, No Bus. Total Fee: 1500)
-- Status: ACTIVE (5, 8, 10, 2 - UNPAID)
INSERT INTO public.student_fees (student_id, class_name, distance, takes_bus, year, month, amount_paid, manual_payment_received, manually_paid, paid) VALUES
('S102', '2', NULL, FALSE, '2024-2025', 4, 1500.00, 0.00, FALSE, FALSE),
('S102', '2', NULL, FALSE, '2024-2025', 5, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S102', '2', NULL, FALSE, '2024-2025', 6, 1500.00, 0.00, FALSE, TRUE),
('S102', '2', NULL, FALSE, '2024-2025', 7, 1500.00, 0.00, FALSE, TRUE),
('S102', '2', NULL, FALSE, '2024-2025', 8, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S102', '2', NULL, FALSE, '2024-2025', 9, 1500.00, 0.00, FALSE, FALSE),
('S102', '2', NULL, FALSE, '2024-2025', 10, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S102', '2', NULL, FALSE, '2024-2025', 11, 1500.00, 0.00, FALSE, FALSE),
('S102', '2', NULL, FALSE, '2024-2025', 12, 1500.00, 0.00, FALSE, FALSE),
('S102', '2', NULL, FALSE, '2024-2025', 1, 1500.00, 0.00, FALSE, TRUE),
('S102', '2', NULL, FALSE, '2024-2025', 2, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S102', '2', NULL, FALSE, '2024-2025', 3, 1500.00, 0.00, FALSE, FALSE);

-- S103: Aisha Singh (Class 1, Upcoming)
-- NO FEE RECORDS GENERATED for 2024-2025.

-- S104: Rahul Khan (Class 3, Takes Bus. Total Fee: 2300)
-- Status: INACTIVE (Left Dec 2024. Active 9 months: 4, 8, 12, 1 - UNPAID, but only 9 months active)
INSERT INTO public.student_fees (student_id, class_name, distance, takes_bus, year, month, amount_paid, manual_payment_received, manually_paid, paid) VALUES
('S104', '3', 8.9, TRUE, '2024-2025', 4, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S104', '3', 8.9, TRUE, '2024-2025', 5, 2300.00, 0.00, FALSE, TRUE),
('S104', '3', 8.9, TRUE, '2024-2025', 6, 2300.00, 0.00, FALSE, TRUE),
('S104', '3', 8.9, TRUE, '2024-2025', 7, 2300.00, 0.00, FALSE, TRUE),
('S104', '3', 8.9, TRUE, '2024-2025', 8, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S104', '3', 8.9, TRUE, '2024-2025', 9, 2300.00, 0.00, FALSE, TRUE),
('S104', '3', 8.9, TRUE, '2024-2025', 10, 2300.00, 0.00, FALSE, TRUE),
('S104', '3', 8.9, TRUE, '2024-2025', 11, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S104', '3', 8.9, TRUE, '2024-2025', 12, 0.00, 0.00, FALSE, FALSE); -- UNPAID (Last month)

-- S105: Samir Iyer (Class 1, Takes Bus. Total Fee: 2300)
-- Status: ACTIVE (4, 7, 10, 1 - UNPAID, with one manual payment)
INSERT INTO public.student_fees (student_id, class_name, distance, takes_bus, year, month, amount_paid, manual_payment_received, manually_paid, paid) VALUES
('S105', '1', 3.5, TRUE, '2024-2025', 4, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S105', '1', 3.5, TRUE, '2024-2025', 5, 2300.00, 0.00, FALSE, TRUE),
('S105', '1', 3.5, TRUE, '2024-2025', 6, 2300.00, 0.00, FALSE, TRUE),
('S105', '1', 3.5, TRUE, '2024-2025', 7, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S105', '1', 3.5, TRUE, '2024-2025', 8, 2300.00, 0.00, FALSE, TRUE),
('S105', '1', 3.5, TRUE, '2024-2025', 9, 2300.00, 0.00, FALSE, TRUE),
('S105', '1', 3.5, TRUE, '2024-2025', 10, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S105', '1', 3.5, TRUE, '2024-2025', 11, 2300.00, 0.00, FALSE, TRUE),
('S105', '1', 3.5, TRUE, '2024-2025', 12, 2300.00, 2300.00, TRUE, TRUE), -- PAID MANUALLY
('S105', '1', 3.5, TRUE, '2024-2025', 1, 0.00, 0.00, FALSE, FALSE), -- UNPAID
('S105', '1', 3.5, TRUE, '2024-2025', 2, 2300.00, 0.00, FALSE, TRUE),
('S105', '1', 3.5, TRUE, '2024-2025', 3, 2300.00, 0.00, FALSE, TRUE);



INSERT INTO public.student (
    student_id, class_name, created_at, distance, dob, email, father_name, gender, joining_date, leaving_date, mother_name, name, phone_number, status, takes_bus, updated_at
) VALUES
-- 1. ACTIVE Student (Class 3) - Family: VERMA - Takes Bus (Distance required)
('S101', '3', CURRENT_TIMESTAMP, 5.5, '2016-01-10', 'vikas.verma@mail.com', 'Gaurav Verma', 'MALE', '2023-07-01', NULL, 'Priya Verma', 'Vikas', '9000111222', 'ACTIVE', TRUE, CURRENT_TIMESTAMP),

-- 2. ACTIVE Student (Class 2) - Family: SHARMA - Does NOT Take Bus (Distance is NULL)
('S102', '2', CURRENT_TIMESTAMP, NULL, '2017-04-25', 'neha.sharma@mail.com', 'Suresh Sharma', 'FEMALE', '2023-08-15', '2026-06-30', 'Kavita Sharma', 'Neha', '9000333444', 'ACTIVE', FALSE, CURRENT_TIMESTAMP),

-- 3. UPCOMING Student (Class 1) - Family: SINGH - Does NOT Take Bus (Distance is NULL)
('S103', '1', CURRENT_TIMESTAMP, NULL, '2019-10-05', 'aisha.singh@mail.com', 'Manish Singh', 'FEMALE', '2026-01-10', NULL, 'Ritu Singh', 'Aisha', '9000555666', 'UPCOMING', FALSE, CURRENT_TIMESTAMP),

-- 4. INACTIVE Student (Class 3) - Family: KHAN - Takes Bus (Distance kept for historical data/fees)
('S104', '3', CURRENT_TIMESTAMP, 8.9, '2016-06-12', 'rahul.kumar@mail.com', 'Kamal Khan', 'MALE', '2022-04-01', '2024-12-01', 'Deepa Khan', 'Rahul', '9000777888', 'INACTIVE', TRUE, CURRENT_TIMESTAMP),

-- 5. ACTIVE Student (Class 1) - Family: IYER - Takes Bus (Distance required)
('S105', '1', CURRENT_TIMESTAMP, 3.5, '2018-03-20', 'samir.iyer@mail.com', 'Prakash Iyer', 'MALE', '2024-07-01', NULL, 'Shanti Iyer', 'Samir', '9000999000', 'ACTIVE', TRUE, CURRENT_TIMESTAMP);


INSERT INTO public.users (user_id, email, password, role) VALUES
('T001', 'sangeeta.sharma@school.edu', 'password123', 'TEACHER'),
('T002', 'rajesh.kumar@school.edu', 'password123', 'TEACHER'),
('T003', 'anita.singh@school.edu', 'password123', 'TEACHER');