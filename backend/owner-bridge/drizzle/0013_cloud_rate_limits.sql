-- Private traffic accounting. Cold campaign recovery starts with fresh budgets.
-- Only the gateway writes these rows. '*' is the owner-wide QA enrollment scope.
CREATE TABLE cloud_rate_buckets (
  owner_id TEXT NOT NULL CHECK (length(owner_id) > 0),
  continuity_id TEXT NOT NULL CHECK (length(continuity_id) BETWEEN 1 AND 128),
  subject TEXT NOT NULL CHECK (subject IN ('sites-owner','sites-mcp') OR (substr(subject,1,7)='device:' AND length(subject) BETWEEN 8 AND 135)),
  next_ms INTEGER NOT NULL CHECK (typeof(next_ms)='integer' AND next_ms BETWEEN 0 AND 9007199254740991),
  PRIMARY KEY (owner_id,continuity_id,subject),
  CHECK (continuity_id<>'*' OR subject='sites-owner')
) WITHOUT ROWID;
