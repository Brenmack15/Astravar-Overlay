"""Local synthetic acceptance harness, NOT a deployable production auth service.

No provider account, existing DM Bridge database, or real campaign is accessed.
Native clients require HTTPS; tests use loopback HTTP to exercise this reference
wire contract independently. An owner OAuth/enrollment gateway is still required.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MODE = "ASTRAVAR_OWNER_SYNC_V1"
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
MAX_BODY = 65536


class Denied(Exception):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code
        super().__init__(code)


def canonical(value):
    def normalize(element):
        if isinstance(element, dict):
            # Kotlin/Java and JavaScript sort strings by UTF-16 code units.
            return {key: normalize(element[key]) for key in sorted(element, key=lambda key: key.encode("utf-16-be"))}
        elif isinstance(element, list):
            return [normalize(entry) for entry in element]
        elif type(element) is int:
            if not -9007199254740991 <= element <= 9007199254740991:
                raise ValueError("Out-of-range integer")
        elif isinstance(element, float):
            raise ValueError("Sync v1 numeric values must be integers")
        return element
    return json.dumps(normalize(value), separators=(",", ":"), ensure_ascii=False, allow_nan=False)


class SyntheticOwnerService:
    """One authoritative SQLite store for fictional records and immutable receipts.

    Separate per-device opaque credentials are stored as hashes and revocable.
    This is NOT OAuth, Sites auth, a production host, or an item/currency engine.
    """

    def __init__(self, path, now=time.time):
        self.now = now
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.db.executescript("""
            PRAGMA foreign_keys=ON;
            CREATE TABLE IF NOT EXISTS credentials (
                id TEXT PRIMARY KEY, token_hash TEXT NOT NULL, campaign TEXT NOT NULL,
                can_write INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS records (
                campaign TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL,
                fields_json TEXT NOT NULL, PRIMARY KEY (campaign,id));
            CREATE TABLE IF NOT EXISTS events (
                campaign TEXT NOT NULL, cursor INTEGER NOT NULL, command_id TEXT NOT NULL,
                fingerprint TEXT NOT NULL, receipt_json TEXT NOT NULL, event_json TEXT NOT NULL,
                PRIMARY KEY(campaign,cursor), UNIQUE(campaign,command_id));
        """)

    def close(self):
        self.db.close()

    @staticmethod
    def campaign(value):
        if not isinstance(value, str) or not ID.fullmatch(value) or not value.startswith("synthetic-"):
            raise Denied(403, "SYNTHETIC_SCOPE_ONLY")
        return value

    def enroll_fixture_device(self, campaign, write=True, lifetime=3600):
        """Test fixture provisioning only. Production requires owner-approved enrollment."""
        campaign = self.campaign(campaign)
        token, credential_id = secrets.token_urlsafe(32), str(uuid.uuid4())
        with self.lock:
            self.db.execute("INSERT INTO credentials VALUES (?,?,?,?,?,0)",
                            (credential_id, hashlib.sha256(token.encode()).hexdigest(), campaign,
                             int(write), int(self.now()) + lifetime))
        return credential_id, token

    def revoke_fixture_device(self, credential_id):
        with self.lock:
            self.db.execute("UPDATE credentials SET revoked=1 WHERE id=?", (credential_id,))

    def authorize(self, credential_id, bearer, campaign, write=False):
        self.campaign(campaign)
        row = self.db.execute("SELECT * FROM credentials WHERE id=?", (credential_id,)).fetchone()
        digest = hashlib.sha256(bearer.encode()).hexdigest()
        if not row or not hmac.compare_digest(row["token_hash"], digest) or row["revoked"] or row["expires_at"] <= self.now():
            raise Denied(401, "CREDENTIAL_REVOKED_OR_EXPIRED")
        if row["campaign"] != campaign or (write and not row["can_write"]):
            raise Denied(403, "ACCESS_DENIED")

    def execute(self, credential_id, token, campaign, body):
        with self.lock:
            self.authorize(credential_id, token, campaign, write=True)
            if not isinstance(body, str) or len(body.encode()) > MAX_BODY:
                raise Denied(400, "INVALID_COMMAND")
            try:
                data = json.loads(body, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                if set(data) != {"v", "campaignId", "commandId", "recordId", "expectedRevision", "operation", "confirmed", "source", "fields"}:
                    raise ValueError()
                if data["v"] != 1 or isinstance(data["v"], bool) or data["campaignId"] != campaign or data["operation"] != "replace_confirmed_record" or data["confirmed"] is not True:
                    raise ValueError()
                if str(uuid.UUID(data["commandId"])) != data["commandId"] or not ID.fullmatch(data["recordId"]):
                    raise ValueError()
                if type(data["expectedRevision"]) is not int or data["expectedRevision"] < 0 or data["expectedRevision"] > 9007199254740990:
                    raise ValueError()
                if not isinstance(data["fields"], dict) or not data["fields"] or any(k.startswith("_") for k in data["fields"]):
                    raise ValueError()
                if not isinstance(data["source"], str) or not data["source"].strip() or len(data["source"]) > 2000:
                    raise ValueError()
                # Exact canonical bytes are the idempotency fingerprint contract.
                if canonical(data) != body:
                    raise ValueError()
            except (ValueError, TypeError, KeyError, AttributeError):
                raise Denied(400, "INVALID_COMMAND") from None
            fingerprint = hashlib.sha256(body.encode()).hexdigest()
            self.db.execute("BEGIN IMMEDIATE")
            try:
                previous = self.db.execute("SELECT * FROM events WHERE campaign=? AND command_id=?", (campaign, data["commandId"])).fetchone()
                if previous:
                    if not hmac.compare_digest(previous["fingerprint"], fingerprint):
                        raise Denied(409, "COMMAND_ID_REUSED")
                    self.db.execute("COMMIT")
                    return {"mode": MODE, "campaignId": campaign, "receipt": json.loads(previous["receipt_json"])}
                record = self.db.execute("SELECT * FROM records WHERE campaign=? AND id=?", (campaign, data["recordId"])).fetchone()
                revision = record["revision"] if record else 0
                if revision != data["expectedRevision"]:
                    raise Denied(409, "REVISION_CONFLICT")
                cursor = self.db.execute("SELECT COALESCE(MAX(cursor),0)+1 FROM events WHERE campaign=?", (campaign,)).fetchone()[0]
                receipt = {"commandId": data["commandId"], "fingerprint": fingerprint,
                           "eventId": str(uuid.uuid4()), "recordId": data["recordId"], "priorRevision": revision,
                           "resultingRevision": revision + 1, "cursor": cursor, "recordedAt": int(self.now())}
                event = dict(receipt, campaignId=campaign, fields=data["fields"], source=data["source"],
                             credentialId=credential_id, confirmed=True)
                self.db.execute("INSERT INTO records VALUES (?,?,?,?) ON CONFLICT(campaign,id) DO UPDATE SET revision=excluded.revision, fields_json=excluded.fields_json",
                                (campaign, data["recordId"], revision + 1, canonical(data["fields"])))
                self.db.execute("INSERT INTO events VALUES (?,?,?,?,?,?)",
                                (campaign, cursor, data["commandId"], fingerprint, canonical(receipt), canonical(event)))
                self.db.execute("COMMIT")
                return {"mode": MODE, "campaignId": campaign, "receipt": receipt}
            except BaseException:
                if self.db.in_transaction:
                    self.db.execute("ROLLBACK")
                raise

    def records(self, credential_id, token, campaign):
        with self.lock:
            self.authorize(credential_id, token, campaign)
            rows = self.db.execute("SELECT * FROM records WHERE campaign=? ORDER BY id", (campaign,)).fetchall()
            return {"mode": MODE, "campaignId": campaign, "records": [
                {"recordId": r["id"], "revision": r["revision"], "fields": json.loads(r["fields_json"])} for r in rows]}

    def changes(self, credential_id, token, campaign, after=0, limit=50):
        with self.lock:
            self.authorize(credential_id, token, campaign)
            if type(after) is not int or type(limit) is not int or after < 0 or not 1 <= limit <= 50:
                raise Denied(400, "INVALID_CURSOR")
            current = self.db.execute("SELECT COALESCE(MAX(cursor),0) FROM events WHERE campaign=?", (campaign,)).fetchone()[0]
            if after > current:
                raise Denied(409, "INVALID_CURSOR")
            rows = self.db.execute("SELECT * FROM events WHERE campaign=? AND cursor>? ORDER BY cursor LIMIT ?", (campaign, after, limit)).fetchall()
            next_cursor = rows[-1]["cursor"] if rows else after
            return {"mode": MODE, "campaignId": campaign, "events": [json.loads(r["event_json"]) for r in rows],
                    "currentCursor": current, "nextCursor": next_cursor, "hasMore": next_cursor < current}


def loopback_server(service):
    """No public bind option. Tests start/stop this explicitly; requests are never logged."""
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def respond(self, status, value):
            body = canonical(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def handle_request(self, write):
            try:
                if self.headers.get("Origin"):
                    raise Denied(403, "BROWSER_ORIGIN_DENIED")
                path = urlsplit(self.path)
                match = re.fullmatch(r"/v1/campaigns/([A-Za-z0-9._-]+)/(commands|records|changes)", path.path)
                if not match:
                    raise Denied(404, "NOT_FOUND")
                campaign, action = match.groups()
                authorization = self.headers.get("Authorization", "")
                if not authorization.startswith("Bearer "):
                    raise Denied(401, "AUTHENTICATION_REQUIRED")
                credential_id, token = self.headers.get("X-Astravar-Credential", ""), authorization[7:]
                if write:
                    if action != "commands" or self.headers.get("Content-Type") != "application/json":
                        raise Denied(400, "INVALID_REQUEST")
                    try:
                        length = int(self.headers.get("Content-Length", "0"))
                    except ValueError:
                        raise Denied(400, "INVALID_REQUEST") from None
                    if not 0 < length <= MAX_BODY:
                        raise Denied(400, "INVALID_REQUEST")
                    value = service.execute(credential_id, token, campaign, self.rfile.read(length).decode("utf-8"))
                elif action == "records":
                    value = service.records(credential_id, token, campaign)
                elif action == "changes":
                    query = parse_qs(path.query)
                    try:
                        after, limit = int(query.get("after", ["0"])[0]), int(query.get("limit", ["50"])[0])
                    except ValueError:
                        raise Denied(400, "INVALID_CURSOR") from None
                    value = service.changes(credential_id, token, campaign, after, limit)
                else:
                    raise Denied(405, "METHOD_NOT_ALLOWED")
                self.respond(200, value)
            except Denied as denied:
                self.respond(denied.status, {"error": {"code": denied.code}})
            except (UnicodeError, ValueError):
                self.respond(400, {"error": {"code": "INVALID_REQUEST"}})

        def do_GET(self):
            self.handle_request(False)

        def do_POST(self):
            self.handle_request(True)

    return ThreadingHTTPServer(("127.0.0.1", 0), Handler)
