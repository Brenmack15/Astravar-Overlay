CREATE TABLE IF NOT EXISTS bridge_bootstrap_downloads (
 ticket_hash TEXT PRIMARY KEY, owner_id TEXT NOT NULL, envelope_json TEXT NOT NULL,
 expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS bridge_bootstrap_downloads_owner_expiry
 ON bridge_bootstrap_downloads(owner_id,expires_at);
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_bootstrap_downloads_quota BEFORE INSERT ON bridge_bootstrap_downloads
 WHEN (SELECT COUNT(*) FROM bridge_bootstrap_downloads WHERE owner_id=NEW.owner_id
  AND expires_at>NEW.created_at)>=8
 BEGIN SELECT RAISE(ABORT,'BOOTSTRAP_DOWNLOAD_LIMIT'); END;
