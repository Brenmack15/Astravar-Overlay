"""Read an isolated Android cache copy while the device application remains force-stopped."""
import json
import sqlite3
import sys
from contextlib import closing
from pathlib import Path


def verify(path):
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        if db.execute("PRAGMA quick_check").fetchone() != ("ok",):
            raise ValueError("The stopped native cache failed SQLite integrity validation.")
        meta = db.execute("SELECT continuity,epoch,revision,token FROM workspace WHERE slot=1").fetchone()
        if not meta or meta[:3] != ("qa-api37-reconnect", 1, 4) or not meta[3]:
            raise ValueError("The stopped cache must contain the verified pre-stop snapshot.")
        rows = {key: json.loads(body)["data"] for key, body in
                db.execute("SELECT id,body FROM records WHERE token=?", (meta[3],))}
        coins = rows["native-wallet"]["coins"]
        intelligence = rows["native-holder"]["abilities"]["INT"]
        remaining = rows["native-pool"]["current"]
        if (coins != {"cp":0, "sp":0, "ep":0, "gp":12, "pp":0} or intelligence != 12 or remaining != 3
                or {"native-key", "native-event", "native-approved"}.intersection(rows)):
            raise ValueError("The stopped cache must retain the confirmed starting balances and rows.")
        return {"epoch":meta[1], "cachedRevision":meta[2], "source":"independent stopped Android SQLite cache",
                "intelligence":intelligence, "gp":coins["gp"], "remaining":remaining, "purchaseAbsent":True, "eventAbsent":True}


if __name__ == "__main__":
    print(json.dumps(verify(Path(sys.argv[1]))))
