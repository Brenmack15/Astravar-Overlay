CREATE TABLE cloud_media_previews (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, upload_id TEXT NOT NULL,
  digest TEXT NOT NULL, target_id TEXT NOT NULL, target_kind TEXT NOT NULL, target_revision INTEGER NOT NULL,
  requested_at INTEGER NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,upload_id),
  FOREIGN KEY(owner_id,continuity_id,upload_id) REFERENCES cloud_media_uploads(owner_id,continuity_id,upload_id) ON DELETE CASCADE
) WITHOUT ROWID;
--> statement-breakpoint
-- A cleanup may begin while a connector prepares its proposal. Validate the
-- exact candidate again atomically when saving a pending approval request.
CREATE TRIGGER cloud_media_request_stage BEFORE INSERT ON cloud_requests
WHEN NEW.status='PENDING_APPROVAL' AND json_extract(NEW.request_json,'$.command.operation')='assign_primary_image'
  AND NOT EXISTS(SELECT 1 FROM cloud_media_uploads u
    WHERE u.owner_id=NEW.owner_id AND u.continuity_id=NEW.continuity_id AND u.state='ready'
    AND u.upload_id=json_extract(NEW.request_json,'$.command.args.uploadId')
    AND u.digest=json_extract(NEW.request_json,'$.command.args.sha256')
    AND u.target_id=json_extract(NEW.request_json,'$.command.args.targetId')
    AND u.target_kind=json_extract(NEW.request_json,'$.command.args.targetKind')
    AND u.epoch=json_extract(NEW.request_json,'$.command.epoch'))
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_CANDIDATE_CHANGED'); END;
