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


INSERT INTO student (student_id, name, email, phone_number, dob, class_name, gender, father_name, mother_name, new_admission) VALUES
('S101', 'Alice Johnson', 'alice.johnson@example.com', '123-456-7890', '2010-05-15', '1', 'Female', 'Robert Johnson', 'Susan Johnson', TRUE),
('S102', 'Bob Smith', 'bob.smith@example.com', '987-654-3210', '2009-12-01', '2', 'Male', 'Michael Smith', 'Linda Smith', FALSE),
('S103', 'Charlie Brown', 'charlie.brown@example.com', '555-123-4567', '2011-03-20', '3', 'Male', 'David Brown', 'Karen Brown', TRUE),
('S104', 'Diana Miller', 'diana.miller@example.com', '111-222-3333', '2010-08-10', '1', 'Female', 'William Miller', 'Patricia Miller', FALSE),
('S105', 'Ethan Davis', 'ethan.davis@example.com', '444-555-6666', '2009-11-25', '3', 'Male', 'James Davis', 'Jennifer Davis', TRUE),
('S106', 'Fiona Wilson', 'fiona.wilson@example.com', '777-888-9999', '2011-01-05', '3', 'Female', 'Richard Wilson', 'Elizabeth Wilson', FALSE),
('S107', 'George Martinez', 'george.martinez@example.com', '222-333-4444', '2010-06-30', '2', 'Male', 'Joseph Martinez', 'Maria Martinez', TRUE),
('S108', 'Hannah Anderson', 'hannah.anderson@example.com', '666-777-8888', '2009-09-18', '1', 'Female', 'Thomas Anderson', 'Barbara Anderson', FALSE),
('S109', 'Ian Taylor', 'ian.taylor@example.com', '333-444-5555', '2011-04-12', '1', 'Male', 'Charles Taylor', 'Dorothy Taylor', TRUE),
('S110', 'Julia Thomas', 'julia.thomas@example.com', '888-999-0000', '2010-07-08', '1', 'Female', 'Christopher Thomas', 'Margaret Thomas', FALSE);



INSERT INTO sibling (student_id, sibling_id) VALUES
('S101', 'S102'),
('S102', 'S101'),
('S103', 'S104'),
('S104', 'S103'),
('S105', 'S106'),
('S106', 'S105'),
('S107', 'S108'),
('S108', 'S107'),
('S109', 'S110'),
('S110', 'S109');



INSERT INTO student_fees (student_id, class_name, discount, month, paid, takes_bus, year, distance) VALUES
-- Student S101 (Class 5)
('S101', '1', 0.00, 1, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 2, true, false, '2023-2024', NULL),
('S101', '1', 0.00, 3, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 4, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 5, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 6, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 7, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 8, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 9, true, false, '2023-2024', NULL),
('S101', '1', 0.00, 10, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 11, false, false, '2023-2024', NULL),
('S101', '1', 0.00, 12, false, false, '2023-2024', NULL),
-- Student S102 (Class 3)
('S102', '2', 0.00, 1, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 2, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 3, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 4, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 5, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 6, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 7, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 8, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 9, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 10, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 11, false, true, '2023-2024', 5.0),
('S102', '2', 0.00, 12, false, true, '2023-2024', 5.0),
-- Student S102 (Class 4)
('S102', '3', 0.00, 1, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 2, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 3, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 4, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 5, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 6, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 7, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 8, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 9, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 10, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 11, false, false, '2024-2025', NULL),
('S102', '3', 0.00, 12, false, false, '2024-2025', NULL),
-- Student S101 (Class 6)
('S101', '2', 5.00, 1, false, true, '2024-2025', 10.0),
('S101', '2', 10.00, 2, false, true, '2024-2025', 10.0),
('S101', '2', 5.00, 3, false, true, '2024-2025', 10.0),
('S101', '2', 15.00, 4, false, true, '2024-2025', 10.0),
('S101', '2', 5.00, 5, false, true, '2024-2025', 10.0),
('S101', '2', 5.00, 6, false, true, '2024-2025', 10.0),
('S101', '2', 10.00, 7, false, true, '2024-2025', 10.0),
('S101', '2', 0.00, 8, false, false, '2024-2025', NULL),
('S101', '2', 0.00, 9, true, false, '2024-2025', NULL),
('S101', '2', 0.00, 10, false, false, '2024-2025', NULL),
('S101', '2', 0.00, 11, false, false, '2024-2025', NULL),
('S101', '2', 0.00, 12, false, false, '2024-2025', NULL);
