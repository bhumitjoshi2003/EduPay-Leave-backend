-- Nullable correlation field: only populated for trace events shipped from the fee-reminder
-- workflow (routers/workflows_fee_reminders.py sends its own Trace-equivalent, tagged with
-- the same workflow_id across its start/resume requests, alongside the existing
-- conversation_id correlation used by ordinary chat turns).

ALTER TABLE ai_trace_event ADD COLUMN workflow_id VARCHAR(64);
CREATE INDEX idx_ai_trace_workflow_id ON ai_trace_event(workflow_id);
