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


INSERT INTO student (student_id, name, email, phone_number, dob, class_name, gender, father_name, mother_name, takes_bus, distance) VALUES
('S101', 'Alice Johnson', 'bhashitbhumit@gmail.com', '7906341843', '2010-05-15', '1', 'Female', 'Robert Johnson', 'Susan Johnson', 1, 5.2),
('S102', 'Bob Smith', 'bob.smith@example.com', '987-654-3210', '2009-12-01', '2', 'Male', 'Michael Smith', 'Linda Smith', 0, NULL),
('S103', 'Charlie Brown', 'charlie.brown@example.com', '555-123-4567', '2011-03-20', '3', 'Male', 'David Brown', 'Karen Brown', 1, 10.5),
('S104', 'Diana Miller', 'diana.miller@example.com', '111-222-3333', '2010-08-10', '1', 'Female', 'William Miller', 'Patricia Miller', 0, NULL),
('S105', 'Ethan Davis', 'ethan.davis@example.com', '444-555-6666', '2009-11-25', '3', 'Male', 'James Davis', 'Jennifer Davis', 1, 7.8),
('S106', 'Fiona Wilson', 'fiona.wilson@example.com', '777-888-9999', '2011-01-05', '3', 'Female', 'Richard Wilson', 'Elizabeth Wilson', 0, NULL),
('S107', 'George Martinez', 'george.martinez@example.com', '222-333-4444', '2010-06-30', '2', 'Male', 'Joseph Martinez', 'Maria Martinez', 1, 3.0),
('S108', 'Hannah Anderson', 'hannah.anderson@example.com', '666-777-8888', '2009-09-18', '1', 'Female', 'Thomas Anderson', 'Barbara Anderson', 0, NULL),
('S109', 'Ian Taylor', 'ian.taylor@example.com', '333-444-5555', '2011-04-12', '1', 'Male', 'Charles Taylor', 'Dorothy Taylor', 1, 12.1),
('S110', 'Julia Thomas', 'julia.thomas@example.com', '888-999-0000', '2010-07-08', '1', 'Female', 'Christopher Thomas', 'Margaret Thomas', 0, NULL);


INSERT INTO teacher (teacher_id, name, email, phone_number, dob, gender, class_teacher, created_at, updated_at) VALUES
('T101', 'Mr. John Smith', 'bhumitjoshi2003@gmail.com', '7906341843', '1985-08-20', 'Male', '1', NOW(), NOW()),
('T102', 'Ms. Jane Doe', 'jane.doe@example.com', '555-123-4567', '1992-03-10', 'Female', '2', NOW(), NOW()),
('T103', 'Mr. David Lee', 'david.lee@example.com', '111-222-3333', '1988-11-25', 'Male', '3', NOW(), NOW()),
('T104', 'Ms. Sarah Williams', 'sarah.williams@example.com', '444-555-6666', '1990-07-01', 'Female', '4', NOW(), NOW()),
('T105', 'Mr. Michael Brown', 'michael.brown@example.com', '777-888-9999', '1987-04-05', 'Male', NULL, NOW(), NOW());


INSERT INTO admin (admin_id, name, email, phone_number, dob, gender, created_at, updated_at) VALUES
('A101', 'Jane Doe', 'jane.doe@example.com', '555-123-4567', '1990-05-15', 'Female', NOW(), NOW()),
('A102', 'John Smith', 'john.smith@example.com', '987-654-3210', '1988-10-22', 'Male', NOW(), NOW());


--INSERT INTO sibling (student_id, sibling_id) VALUES
--('S101', 'S102'),
--('S102', 'S101'),
--('S103', 'S104'),
--('S104', 'S103'),
--('S105', 'S106'),
--('S106', 'S105'),
--('S107', 'S108'),
--('S108', 'S107'),
--('S109', 'S110'),
--('S110', 'S109');


--
--INSERT INTO student_fees (student_id, class_name, month, paid, takes_bus, year, distance, manually_paid, manual_payment_received) VALUES
---- Student S101 (Class 1 - Assuming it was Class 1 in '2023-2024')
--('S101', '1', 1, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 2, true, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 3, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 4, true, false, '2023-2024', NULL, true, 500.00), -- Example of manually paid
--('S101', '1', 5, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 6, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 7, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 8, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 9, true, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 10, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 11, false, false, '2023-2024', NULL, false, NULL),
--('S101', '1', 12, false, false, '2023-2024', NULL, false, NULL),
---- Student S102 (Class 2 - Assuming it was Class 2 in '2023-2024')
--('S102', '2', 1, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 2, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 3, true, true, '2023-2024', 5.0, true, 750.00), -- Example of manually paid
--('S102', '2', 4, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 5, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 6, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 7, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 8, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 9, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 10, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 11, false, true, '2023-2024', 5.0, false, NULL),
--('S102', '2', 12, false, true, '2023-2024', 5.0, false, NULL),
---- Student S102 (Class 3 in '2024-2025')
--('S102', '3', 1, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 2, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 3, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 4, true, false, '2024-2025', NULL, true, 600.00), -- Example of manually paid
--('S102', '3', 5, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 6, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 7, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 8, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 9, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 10, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 11, false, false, '2024-2025', NULL, false, NULL),
--('S102', '3', 12, false, false, '2024-2025', NULL, false, NULL),
---- Student S101 (Class 2 - Assuming it was Class 2 in '2024-2025')
--('S101', '2', 1, false, true, '2024-2025', 10.0, false, NULL),
--('S101', '2', 2, false, true, '2024-2025', 10.0, false, NULL),
--('S101', '2', 3, true, true, '2024-2025', 10.0, true, 800.00), -- Example of manually paid
--('S101', '2', 4, false, true, '2024-2025', 10.0, false, NULL),
--('S101', '2', 5, false, true, '2024-2025', 10.0, false, NULL),
--('S101', '2', 6, false, true, '2024-2025', 10.0, false, NULL),
--('S101', '2', 7, false, true, '2024-2025', 10.0, false, NULL),
--('S101', '2', 8, false, false, '2024-2025', NULL, false, NULL),
--('S101', '2', 9, true, false, '2024-2025', NULL, false, NULL),
--('S101', '2', 10, false, false, '2024-2025', NULL, false, NULL),
--('S101', '2', 11, false, false, '2024-2025', NULL, false, NULL),
--('S101', '2', 12, false, false, '2024-2025', NULL, false, NULL);