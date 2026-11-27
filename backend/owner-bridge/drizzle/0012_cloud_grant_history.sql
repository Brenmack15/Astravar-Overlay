-- Security history remains separate from gameplay revision and from effective grants.
CREATE TABLE cloud_grant_audit (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  subject TEXT NOT NULL,
  grant_revision INTEGER NOT NULL,
  scopes_json TEXT NOT NULL CHECK(json_valid(scopes_json)),
  actors_json TEXT NOT NULL CHECK(json_valid(actors_json)),
  auto_json TEXT NOT NULL CHECK(json_valid(auto_json)),
  expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL CHECK(revoked IN (0,1)),
  recorded_at INTEGER NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,event_id),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_grant_audit_by_time ON cloud_grant_audit(owner_id,continuity_id,recorded_at,event_id);
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_insert AFTER INSERT ON cloud_grants
BEGIN
 INSERT INTO cloud_grant_audit(owner_id,continuity_id,event_id,epoch,subject,grant_revision,scopes_json,actors_json,auto_json,expires_at,revoked,recorded_at)
 SELECT NEW.owner_id,NEW.continuity_id,lower(hex(randomblob(16))),c.epoch,NEW.subject,NEW.revision,NEW.scopes_json,NEW.actors_json,NEW.auto_json,NEW.expires_at,NEW.revoked,CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)
 FROM cloud_continuities c WHERE c.owner_id=NEW.owner_id AND c.continuity_id=NEW.continuity_id;
END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_update AFTER UPDATE ON cloud_grants
BEGIN
 INSERT INTO cloud_grant_audit(owner_id,continuity_id,event_id,epoch,subject,grant_revision,scopes_json,actors_json,auto_json,expires_at,revoked,recorded_at)
 SELECT NEW.owner_id,NEW.continuity_id,lower(hex(randomblob(16))),c.epoch,NEW.subject,NEW.revision,NEW.scopes_json,NEW.actors_json,NEW.auto_json,NEW.expires_at,NEW.revoked,CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)
 FROM cloud_continuities c WHERE c.owner_id=NEW.owner_id AND c.continuity_id=NEW.continuity_id;
END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_no_update BEFORE UPDATE ON cloud_grant_audit
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_AUDIT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_no_delete BEFORE DELETE ON cloud_grant_audit
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_AUDIT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_no_replace BEFORE INSERT ON cloud_grant_audit
WHEN EXISTS(SELECT 1 FROM cloud_grant_audit WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND event_id=NEW.event_id)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_AUDIT'); END;
