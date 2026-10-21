CREATE TABLE IF NOT EXISTS bridge_campaign_push_tokens(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,grant_id TEXT NOT NULL,credential_id TEXT NOT NULL,token_hash TEXT NOT NULL,token_iv TEXT NOT NULL,token_ciphertext TEXT NOT NULL,notifications_enabled INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,last_push_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner_id,campaign_id,credential_id));
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS bridge_campaign_push_grant ON bridge_campaign_push_tokens(grant_id,campaign_id);

--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_credential_revoke AFTER UPDATE OF revoked ON bridge_credentials WHEN NEW.revoked=1 BEGIN DELETE FROM bridge_campaign_push_tokens WHERE credential_id=NEW.id AND owner_id=NEW.owner_id; END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_grant_revoke AFTER UPDATE OF revoked ON bridge_campaign_grants WHEN NEW.revoked=1 BEGIN DELETE FROM bridge_campaign_push_tokens WHERE grant_id=NEW.id AND owner_id=NEW.owner_id; END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_credential_delete AFTER DELETE ON bridge_credentials BEGIN DELETE FROM bridge_campaign_push_tokens WHERE credential_id=OLD.id AND owner_id=OLD.owner_id; END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_grant_delete AFTER DELETE ON bridge_campaign_grants BEGIN DELETE FROM bridge_campaign_push_tokens WHERE grant_id=OLD.id AND owner_id=OLD.owner_id; END;

--> statement-breakpoint
CREATE INDEX IF NOT EXISTS bridge_campaign_session_checkpoint ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,COALESCE(json_extract(fields_json,'$.updatedAt'),0) DESC,record_id);
