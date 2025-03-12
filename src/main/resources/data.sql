--INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2023-2024', 0, 3, 500);
--INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2024-2025', 0, 3, 600);
--INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2023-2024', 4, 8, 700);
--INSERT INTO bus_fees (academic_year, min_distance, max_distance, fees) VALUES ('2024-2025', 4, 8, 800);


INSERT INTO fee_structure (academic_year, class_name, tuition_fee, admission_fee, annual_charges, eca_project, examination_fee, lab_charges)
VALUES
    ('2023-24', 'Nursery', 10000.00, 5000.00, 2000.00, 1000.00, 500.00, 0.00),
    ('2023-24', 'LKG', 12000.00, 5500.00, 2200.00, 1200.00, 600.00, 0.00),
    ('2023-24', 'UKG', 14000.00, 6000.00, 2400.00, 1400.00, 700.00, 0.00),
    ('2024-25', 'Nursery', 11000.00, 5500.00, 2500.00, 1200.00, 600.00, 0.00),
    ('2024-25', 'LKG', 13000.00, 6000.00, 2700.00, 1500.00, 700.00, 0.00),
    ('2024-25', 'UKG', 15000.00, 6500.00, 2900.00, 1700.00, 800.00, 0.00);