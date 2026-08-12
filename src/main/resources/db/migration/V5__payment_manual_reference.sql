-- Manual-payment hardening phase: admin-recorded (cash/cheque/UPI/bank-transfer) payments
-- previously had no dedicated field to record how the money arrived or a reference number
-- for duplicate detection — everything went through the generic, system-generated
-- payment_id (a UUID, never admin-supplied, so it could not be used to catch a re-entered
-- cheque/UTR number). These two nullable columns are populated only for manually-recorded
-- payments; Razorpay-path rows leave them NULL.
ALTER TABLE payment ADD COLUMN IF NOT EXISTS manual_payment_mode VARCHAR(30);
ALTER TABLE payment ADD COLUMN IF NOT EXISTS manual_reference_number VARCHAR(100);
