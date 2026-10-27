-- Report delivery (MS §11.8): a job queued by a schedule records it, and the schedule's owner may be e-mailed when the
-- job finishes or fails. The e-mail carries a link to My reports, never the report itself.
ALTER TABLE report_jobs ADD COLUMN schedule_id UUID REFERENCES report_schedules (id);
ALTER TABLE report_schedules ADD COLUMN email_when_ready BOOLEAN NOT NULL DEFAULT TRUE;
