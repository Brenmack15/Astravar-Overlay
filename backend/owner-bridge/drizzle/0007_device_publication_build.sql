ALTER TABLE bridge_campaign_publications ADD COLUMN device_build_json TEXT;
--> statement-breakpoint
CREATE INDEX bridge_campaign_verified_publication ON bridge_campaign_publications(grant_id,created_at DESC) WHERE expected_generation IS NOT NULL;
