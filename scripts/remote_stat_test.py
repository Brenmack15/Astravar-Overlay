#!/usr/bin/env python3
"""Bounded sender for Astravar's PUBLIC disposable ability-command experiment.

No credentials, real-profile input, arbitrary text, origin override, or sheet export.
`prepare` only reads the service and saves an immutable request before `send` writes.
Keep its state file to recover an uncertain response with the SAME UUID and body.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
import uuid

ORIGIN = "https://astravar-public-disposable.fantasy-layer.workers.dev"
CAMPAIGN = "astravar-public-disposable"
CONNECTION = "sandbox-merchant-0001"
MODE = "PUBLIC_DISPOSABLE_SANDBOX"
BASE = f"/api/v1/campaigns/{CAMPAIGN}/connections/{CONNECTION}"
PROFILE = "remote-stat-test-v1"
SOURCE = "ChatGPT fictional test"
ABILITIES = ("STR", "DEX", "CON", "INT", "WIS", "CHA")
MAX_REVISION = 9_007_199_254_740_990


class ClientError(ValueError):
    pass


class ApiError(ClientError):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code
        super().__init__(f"HTTP {status}: {code}")


def canonical(value: object) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def no_duplicates(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ClientError("Duplicate JSON field rejected.")
        result[key] = value
    return result


def parse_json(value: str) -> dict:
    try:
        result = json.loads(value, object_pairs_hook=no_duplicates,
                            parse_constant=lambda _: (_ for _ in ()).throw(ClientError("Non-finite JSON rejected.")))
    except (ValueError, TypeError) as error:
        raise ClientError("Invalid strict JSON.") from error
    if not isinstance(result, dict):
        raise ClientError("Expected a JSON object.")
    return result


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ClientError(message)


def canonical_uuid(value: str) -> str:
    try:
        require(isinstance(value, str) and str(uuid.UUID(value)) == value, "Use a canonical lowercase UUID.")
    except (ValueError, TypeError, AttributeError) as error:
        raise ClientError("Use a canonical lowercase UUID.") from error
    return value


def integer(value: object, low: int, high: int) -> bool:
    return type(value) is int and low <= value <= high


def command_detail(command_id: str, session_id: str, ability: str,
                   expected_revision: int, score: int) -> str:
    canonical_uuid(command_id)
    canonical_uuid(session_id)
    require(ability in ABILITIES, "Unsupported ability.")
    require(integer(expected_revision, 0, MAX_REVISION), "Invalid local ability revision.")
    require(integer(score, 0, 1000), "Score is outside the existing application's accepted range.")
    return canonical({"type": "astravar.remote-ability", "v": 1, "commandId": command_id,
                      "sessionId": session_id, "profileId": PROFILE, "ability": ability,
                      "expectedRevision": expected_revision, "score": score, "source": SOURCE})


def fingerprint(detail: str, revision: int) -> str:
    value = {"v": 1, "campaignId": CAMPAIGN, "connectionId": CONNECTION, "kind": "new_fact",
             "detail": detail, "expectedRevision": revision, "confirmed": True}
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def prepare_state(session_id: str, ability: str, expected_revision: int,
                  score: int, transport_revision: int, command_id: str | None = None) -> dict:
    require(integer(transport_revision, 1, MAX_REVISION), "Invalid public transport revision.")
    command_id = command_id or str(uuid.uuid4())
    detail = command_detail(command_id, session_id, ability, expected_revision, score)
    body = canonical({"campaignId": CAMPAIGN, "connectionId": CONNECTION, "kind": "new_fact",
                      "detail": detail, "commandId": command_id,
                      "expectedRevision": transport_revision, "confirmed": True})
    return {"formatVersion": 1, "origin": ORIGIN, "body": body,
            "fingerprint": fingerprint(detail, transport_revision)}


def validate_state(state: dict) -> dict:
    require(set(state) == {"formatVersion", "origin", "body", "fingerprint"}
            and type(state["formatVersion"]) is int and state["formatVersion"] == 1
            and state["origin"] == ORIGIN, "Unexpected sender state or destination.")
    require(isinstance(state["body"], str), "Invalid persisted request body.")
    body = parse_json(state["body"])
    require(set(body) == {"campaignId", "connectionId", "kind", "detail", "commandId", "expectedRevision", "confirmed"},
            "Unexpected outer request fields.")
    detail = parse_json(body["detail"])
    require(set(detail) == {"type", "v", "commandId", "sessionId", "profileId", "ability", "expectedRevision", "score", "source"},
            "Unexpected ability command fields.")
    require(detail["type"] == "astravar.remote-ability" and type(detail["v"]) is int and detail["v"] == 1
            and detail["profileId"] == PROFILE and detail["source"] == SOURCE, "Unsupported ability command.")
    expected_detail = command_detail(detail["commandId"], detail["sessionId"], detail["ability"],
                                     detail["expectedRevision"], detail["score"])
    require(body["detail"] == expected_detail and body["commandId"] == detail["commandId"]
            and body["campaignId"] == CAMPAIGN and body["connectionId"] == CONNECTION
            and body["kind"] == "new_fact" and body["confirmed"] is True
            and integer(body["expectedRevision"], 1, MAX_REVISION), "Invalid persisted fictional command.")
    require(state == prepare_state(detail["sessionId"], detail["ability"], detail["expectedRevision"],
                                   detail["score"], body["expectedRevision"], detail["commandId"]),
            "Persisted body or fingerprint changed; nothing was sent.")
    return body


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_request(path: str, body: str | None = None) -> dict:
    require(path.startswith("/") and not path.startswith("//") and "#" not in path,
            "Invalid fixed API path.")
    data = body.encode("utf-8") if body is not None else None
    require(data is None or len(data) <= 8192, "Request too large.")
    headers = {"Accept": "application/json", "User-Agent": "AstravarRemoteStatTest/1"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(ORIGIN + path, data=data, headers=headers)
    try:
        response = urllib.request.build_opener(NoRedirect).open(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        require(response.headers.get_content_type() == "application/json",
                f"HTTP {status}: non-JSON hosting/network response; original UUID and body remain unchanged.")
        raw = response.read(262145)
        require(len(raw) <= 262144, "Response too large.")
        result = parse_json(raw.decode("utf-8"))
    require(result.get("mode") == MODE, "Unexpected service mode.")
    if not 200 <= status <= 299:
        error = result.get("error")
        code = error.get("code", "HTTP_ERROR") if isinstance(error, dict) else "HTTP_ERROR"
        code = code if isinstance(code, str) and re.fullmatch(r"[A-Z0-9_]{1,80}", code) else "HTTP_ERROR"
        raise ApiError(status, code)
    return result


def require_scope(value: dict) -> None:
    require(value.get("mode") == MODE and value.get("campaignId") == CAMPAIGN
            and value.get("connectionId") == CONNECTION, "Unexpected fictional service scope.")


def read_revision(request=http_request) -> int:
    health = request("/health")
    require(health.get("mode") == MODE and health.get("disposable") is True
            and health.get("authentication") == "NONE" and health.get("realCampaignAccess") is False,
            "Service is not the disposable unauthenticated fictional sandbox.")
    connection = request(BASE)
    require_scope(connection)
    require(connection.get("disposable") is True and integer(connection.get("revision"), 1, MAX_REVISION),
            "Invalid fictional connection revision.")
    return connection["revision"]


def validate_receipt(receipt: dict, state: dict) -> dict:
    body = validate_state(state)
    require_scope(receipt)
    require(receipt.get("status") == "COMMITTED" and receipt.get("commandId") == body["commandId"]
            and receipt.get("payloadFingerprint") == state["fingerprint"]
            and type(receipt.get("priorRevision")) is int and receipt["priorRevision"] == body["expectedRevision"]
            and type(receipt.get("resultingRevision")) is int and receipt["resultingRevision"] == body["expectedRevision"] + 1,
            "Receipt does not match the saved request; outcome remains uncertain.")
    canonical_uuid(receipt.get("eventId"))
    require(isinstance(receipt.get("recordedAt"), str) and bool(receipt["recordedAt"]), "Missing receipt timestamp.")
    return receipt


def send(state: dict, request=http_request) -> dict:
    body = validate_state(state)
    receipt_path = f"/api/v1/campaigns/{CAMPAIGN}/commands/{body['commandId']}/receipt"
    try:
        receipt = request(receipt_path)
    except ApiError as error:
        if error.status != 404 or error.code != "COMMAND_NOT_FOUND":
            raise
        receipt = request(BASE + "/milestones", state["body"])
    return validate_receipt(receipt, state)


def read_outcomes(state: dict, request=http_request) -> list[dict]:
    body = validate_state(state)
    command = parse_json(body["detail"])
    cursor, outcomes = 1, []
    for _ in range(25):
        page = request(BASE + f"/changes?afterRevision={cursor}&limit=50")
        require_scope(page)
        require(type(page.get("hasMore")) is bool and integer(page.get("currentRevision"), 1, MAX_REVISION)
                and isinstance(page.get("milestones"), list), "Invalid history response.")
        events = page["milestones"]
        for event in events:
            require(isinstance(event, dict) and type(event.get("priorRevision")) is int
                    and event["priorRevision"] == cursor and type(event.get("resultingRevision")) is int
                    and event["resultingRevision"] == cursor + 1, "History gap; outcome not verified.")
            cursor += 1
            if event.get("kind") != "new_fact" or not isinstance(event.get("detail"), str):
                continue
            try:
                outcome = parse_json(event["detail"])
                require(set(outcome) == {"type", "v", "commandId", "sessionId", "status"}
                        and outcome["type"] == "astravar.remote-ability-result"
                        and type(outcome["v"]) is int and outcome["v"] == 1
                        and outcome["status"] in ("APPLIED", "REJECTED")
                        and event["detail"] == canonical({"type": outcome["type"], "v": outcome["v"],
                            "commandId": outcome["commandId"], "sessionId": outcome["sessionId"],
                            "status": outcome["status"]}), "Not a strict outcome.")
                canonical_uuid(outcome["commandId"])
                canonical_uuid(outcome["sessionId"])
            except (ClientError, KeyError, TypeError):
                continue
            if outcome["commandId"] == command["commandId"] and outcome["sessionId"] == command["sessionId"]:
                outcomes.append(outcome)
        require(type(page.get("nextCursor")) is int and page["nextCursor"] == cursor
                and cursor <= page["currentRevision"] and (not page["hasMore"] or bool(events))
                and (page["hasMore"] or cursor == page["currentRevision"]), "Invalid history cursor.")
        if not page["hasMore"]:
            return outcomes
    raise ClientError("History page limit reached; outcome not verified.")


def save_new(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as output:
        output.write(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
        output.flush()
        import os
        os.fsync(output.fileno())


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    prepare = sub.add_parser("prepare", help="Read public revision and persist request; DOES NOT POST")
    prepare.add_argument("--session-id", required=True)
    prepare.add_argument("--ability", required=True, choices=ABILITIES)
    prepare.add_argument("--expected-ability-revision", required=True, type=int)
    prepare.add_argument("--score", required=True, type=int)
    prepare.add_argument("--state", required=True, type=Path)
    for name in ("send", "status"):
        command = sub.add_parser(name, help="Receipt-first exact retry" if name == "send" else "Read transport receipt and player outcome")
        command.add_argument("--state", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.action == "prepare":
            # Validate before any network access, including protected identity and deliberate fictional score.
            command_detail(str(uuid.uuid4()), args.session_id, args.ability, args.expected_ability_revision, args.score)
            require(not args.state.exists(), "State file already exists; use send/status for its original UUID.")
            state = prepare_state(args.session_id, args.ability, args.expected_ability_revision,
                                  args.score, read_revision())
            save_new(args.state, state)
            body = validate_state(state)
            print(canonical({"prepared": True, "posted": False, "commandId": body["commandId"],
                             "state": str(args.state), "transportRevision": body["expectedRevision"]}))
        else:
            state = parse_json(args.state.read_text(encoding="utf-8"))
            body = validate_state(state)
            if args.action == "send":
                receipt = send(state)
                # The durable request is never rewritten. Keep receipt as separate evidence if not saved yet.
                evidence = args.state.with_name(args.state.name + ".receipt.json")
                if evidence.exists():
                    require(parse_json(evidence.read_text(encoding="utf-8")) == receipt, "Previously saved receipt changed.")
                else:
                    save_new(evidence, receipt)
                print(canonical({"transport": "COMMITTED", "commandId": body["commandId"],
                                 "eventId": receipt["eventId"], "phoneApplication": "NOT_YET_VERIFIED"}))
            else:
                outcomes = read_outcomes(state)
                print(canonical({"commandId": body["commandId"], "playerOutcomes": outcomes,
                                 "meaning": "Reported by unauthenticated public test client; verify on phone."}))
        return 0
    except (ClientError, OSError, urllib.error.URLError, UnicodeError) as error:
        print(f"Remote test stopped: {error}", file=sys.stderr)
        print("Keep the original state file. After an uncertain response, retry send with that same file; do not generate a replacement UUID.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
