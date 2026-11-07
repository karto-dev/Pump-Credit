"""API smoke test for phases 4-7: payments, verification, statements, reports, adjustments, reminders, backups.

It creates customers, users and transactions, so point it at a scratch database, never production:
  docker exec ppcms-postgres createdb -U ppcms petrol_credit_test
  DB_URL=jdbc:postgresql://localhost:5434/petrol_credit_test ADMIN_PASSWORD=<test pw> \
  BACKUP_COMMAND="docker exec ppcms-postgres pg_dump -U ppcms --format=custom petrol_credit_test" \
    java -jar backend/target/credit-backend-0.1.0.jar --server.port=8091
  SMOKE_BASE=http://localhost:8091/api SMOKE_ADMIN_PW=<test pw> python3 scripts/smoke-test.py 45 6 7
"""
import json, os, sys, uuid, urllib.request, urllib.error, datetime

BASE = os.environ.get("SMOKE_BASE", "http://localhost:8091/api")
TODAY = datetime.date.today().isoformat()
FAILS = []


def call(method, path, token=None, body=None, parts=None, expect=None, raw=False):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if parts is not None:
        boundary = uuid.uuid4().hex
        chunks = []
        for name, (fname, ctype, content) in parts.items():
            disp = f'form-data; name="{name}"' + (f'; filename="{fname}"' if fname else "")
            chunks.append(f"--{boundary}\r\nContent-Disposition: {disp}\r\nContent-Type: {ctype}\r\n\r\n".encode() + content + b"\r\n")
        data = b"".join(chunks) + f"--{boundary}--\r\n".encode()
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            status, payload = r.status, r.read()
            ctype = r.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        status, payload, ctype = e.code, e.read(), e.headers.get("Content-Type", "")
    out = payload if raw or "json" not in ctype else (json.loads(payload) if payload else None)
    if expect is not None and status != expect:
        FAILS.append(f"{method} {path}: expected {expect}, got {status}: {payload[:300]!r}")
        print("  FAIL", method, path, status, payload[:300])
    return status, out


def check(cond, msg):
    print(("  ok   " if cond else "  FAIL ") + msg)
    if not cond:
        FAILS.append(msg)


def jpeg():
    return b"\xff\xd8\xff\xe0" + os.urandom(64)


def login(user, pw):
    s, r = call("POST", "/auth/login", body={"username": user, "password": pw}, expect=200)
    return r["token"]


def j(obj):
    return (None, "application/json", json.dumps(obj).encode())


def setup():
    admin = login("admin", os.environ["SMOKE_ADMIN_PW"])
    call("POST", "/fuel-rates", admin, {"fuelType": "DIESEL", "ratePerLitre": 90.00}, expect=None)
    call("POST", "/fuel-rates", admin, {"fuelType": "PETROL", "ratePerLitre": 100.00}, expect=None)
    tag = uuid.uuid4().hex[:6]
    s, cust = call("POST", "/customers", admin, {
        "profile": {"name": f"Smoke Transport {tag}", "phone": "9876500001", "creditPeriodDays": 30},
        "creditLimit": 50000, "openingBalance": 1000}, expect=201)
    cid = cust["id"]
    vno = "KA01SM" + str(uuid.uuid4().int % 10000).zfill(4)
    call("POST", f"/customers/{cid}/vehicles", admin, {"vehicleNumber": vno, "defaultFuel": "DIESEL"}, expect=201)
    tokens = {"admin": admin}
    for role, extra in [("CASHIER", {}), ("MANAGER", {}), ("CUSTOMER", {"customerId": cid})]:
        s, r = call("POST", "/users", admin, {"username": f"smoke.{role.lower()}.{tag}", "fullName": f"Smoke {role.title()}",
                                              "roleCode": role, **extra}, expect=201)
        tokens[role] = login(r["user"]["username"], r["temporaryPassword"])
    return tokens, cid


def balance(tok, cid):
    return float(call("GET", f"/customers/{cid}", tok)[1]["confirmedOutstanding"])


def phase45(t, cid):
    print("Phase 4-5: payments")
    s, veh = call("GET", f"/customers/{cid}/vehicles", t["CASHIER"])
    s, sale = call("POST", "/credit-sales", t["CASHIER"], parts={
        "sale": j({"customerId": cid, "vehicleId": veh[0]["id"], "fuelType": "DIESEL", "quantity": 50, "ratePerLitre": 90}),
        "bill": ("bill.jpg", "image/jpeg", jpeg())}, expect=201)
    check(balance(t["MANAGER"], cid) == 5500.0, "sale + opening balance = 5500")

    utr = "UTR" + uuid.uuid4().hex[:10].upper()
    s, p1 = call("POST", "/payments/submissions", t["CUSTOMER"], parts={
        "payment": j({"paymentDate": TODAY, "amount": 2000, "method": "UPI", "referenceNo": utr}),
        "proof": ("pay.jpg", "image/jpeg", jpeg())}, expect=201)
    check(p1["status"] == "AWAITING_VERIFICATION", "UPI claim goes to verification")
    check(balance(t["MANAGER"], cid) == 5500.0, "unverified claim does not reduce outstanding")

    s, r = call("POST", "/payments/submissions", t["CUSTOMER"], parts={
        "payment": j({"paymentDate": TODAY, "amount": 2000, "method": "UPI", "referenceNo": utr.lower()})})
    check(s == 409 and r.get("duplicate"), "same UTR warns as duplicate")

    call("POST", f"/payments/{p1['id']}/verify", t["CASHIER"], {"remarks": "x"}, expect=403)
    s, v = call("POST", f"/payments/{p1['id']}/verify", t["MANAGER"],
                {"remarks": "Seen in HDFC statement", "evidenceChecked": "HDFC stmt"}, expect=200)
    check(v["status"] == "VERIFIED", "manager verifies UPI")
    check(balance(t["MANAGER"], cid) == 3500.0, "verified payment reduces outstanding to 3500")
    call("POST", f"/payments/{p1['id']}/verify", t["MANAGER"], {"remarks": "again"}, expect=409)

    s, r = call("POST", "/payments/submissions", t["CUSTOMER"], parts={
        "payment": j({"paymentDate": TODAY, "amount": 2000, "method": "UPI", "referenceNo": utr, "confirmDuplicate": True})})
    check(s == 409 and not r.get("duplicate"), "already-verified UTR is refused even with override")

    # Cash claim -> cashier links it -> manager verifies short
    s, p2 = call("POST", "/payments/submissions", t["CUSTOMER"], parts={
        "payment": j({"paymentDate": TODAY, "amount": 500, "method": "CASH"})}, expect=201)
    check(p2["status"] == "AWAITING_CASHIER_ENTRY", "cash claim waits for cashier")
    call("POST", f"/payments/{p2['id']}/verify", t["MANAGER"], {"remarks": "too early"}, expect=409)
    s, p2 = call("POST", "/payments/cashier-entries", t["CASHIER"], parts={
        "entry": j({"customerId": cid, "paymentId": p2["id"], "paymentDate": TODAY, "amount": 500, "method": "CASH"})}, expect=201)
    check(p2["status"] == "AWAITING_VERIFICATION" and p2["entryNo"], "cashier links cash to claim")
    s, p2 = call("POST", f"/payments/{p2['id']}/verify", t["MANAGER"], {"verifiedAmount": 400, "remarks": "Drawer short by 100"}, expect=200)
    check(balance(t["MANAGER"], cid) == 3100.0, "short verification posts only 400")
    call("POST", f"/payments/{p2['id']}/verify", t["MANAGER"], {"verifiedAmount": 600, "remarks": "over"}, expect=409)

    # Maker-checker: admin records and cannot verify own entry
    s, p3 = call("POST", "/payments/cashier-entries", t["admin"], parts={
        "entry": j({"customerId": cid, "paymentDate": TODAY, "amount": 300, "method": "CASH"})}, expect=201)
    call("POST", f"/payments/{p3['id']}/verify", t["admin"], {"remarks": "own"}, expect=403)
    call("POST", f"/payments/{p3['id']}/verify", t["MANAGER"], {"remarks": "ok"}, expect=200)
    check(balance(t["MANAGER"], cid) == 2800.0, "other user verifies admin's entry")

    # Proof request round-trip, then reject; cancel by customer
    s, p4 = call("POST", "/payments/submissions", t["CUSTOMER"], parts={
        "payment": j({"paymentDate": TODAY, "amount": 700, "method": "NEFT", "referenceNo": "N" + uuid.uuid4().hex[:8]})}, expect=201)
    s, p4 = call("POST", f"/payments/{p4['id']}/request-proof", t["MANAGER"], {"remarks": "Send bank slip"}, expect=200)
    check(p4["status"] == "ADDITIONAL_PROOF_REQUIRED", "proof requested")
    s, p4 = call("POST", f"/payments/{p4['id']}/proofs", t["CUSTOMER"], parts={"proof": ("slip.jpg", "image/jpeg", jpeg())}, expect=200)
    check(p4["status"] == "AWAITING_VERIFICATION" and p4["proofCount"] == 1, "proof upload reopens payment")
    s, p4 = call("POST", f"/payments/{p4['id']}/reject", t["MANAGER"], {"remarks": "Not in statement"}, expect=200)
    check(p4["status"] == "REJECTED", "rejected")
    s, p5 = call("POST", "/payments/submissions", t["CUSTOMER"], parts={
        "payment": j({"paymentDate": TODAY, "amount": 100, "method": "CASH"})}, expect=201)
    s, p5 = call("POST", f"/payments/{p5['id']}/cancel", t["CUSTOMER"], {"remarks": "Entered by mistake"}, expect=200)
    check(p5["status"] == "CANCELLED", "customer withdraws claim")
    check(balance(t["MANAGER"], cid) == 2800.0, "rejected/cancelled do not touch outstanding")

    s, hist = call("GET", f"/payments/{p4['id']}/history", t["CUSTOMER"], expect=200)
    check(len(hist) == 3, "history has proof request, reopen, reject")
    s, other = call("GET", "/payments", t["CUSTOMER"], expect=200)
    check(all(p["customerId"] == cid for p in other), "customer sees only own payments")
    s, summ = call("GET", "/payments/summary", t["MANAGER"], expect=200)
    check(float(summ["verifiedToday"]) >= 2700, "summary verified today")
    return sale


def phase6(t, ctx):
    print("Phase 6: statements, reports, controls, adjustments")
    cid = ctx["cid"]
    month_start = datetime.date.today().replace(day=1).isoformat()
    s, st = call("GET", f"/customers/{cid}/statement?from={month_start}&to={TODAY}", t["CUSTOMER"], expect=200)
    closing = [i for i in st["summary"] if i["label"].startswith("Closing")][0]["value"]
    check(float(closing) == 2800.0, f"statement closing = 2800 (got {closing})")
    check(len(st["rows"]) == 5 and st["rows"][-1][6] == 2800.0, "statement has opening, sale, 3 payments; running balance ends 2800")
    call("GET", f"/customers/{cid + 999}/statement", t["CUSTOMER"], expect=404)
    call("GET", f"/customers/{cid}/statement", t["CASHIER"], expect=403)
    s, pdf = call("GET", f"/customers/{cid}/statement?format=pdf", t["CUSTOMER"], expect=200, raw=True)
    check(pdf[:5] == b"%PDF-", "statement PDF download")
    s, xl = call("GET", f"/customers/{cid}/statement?format=xlsx", t["MANAGER"], expect=200, raw=True)
    check(xl[:2] == b"PK", "statement Excel download")

    s, out = call("GET", "/reports/outstanding", t["MANAGER"], expect=200)
    row = [r for r in out["rows"] if r[1].startswith("Smoke")]
    check(any(float(r[5]) == 2800.0 and float(r[8]) == 2800.0 for r in row), "outstanding report: 2800 all in 0-30 bucket")
    call("GET", "/reports/outstanding", t["CASHIER"], expect=403)
    for path in ["/reports/sales?groupBy=day", "/reports/sales?groupBy=customer", "/reports/sales?groupBy=detail",
                 "/reports/collections", "/reports/cashiers"]:
        s, r = call("GET", path, t["MANAGER"], expect=200)
        check(s == 200 and isinstance(r["rows"], list), f"report {path} ({len(r['rows'])} rows)")
        s, b = call("GET", path + ("&" if "?" in path else "?") + "format=xlsx", t["MANAGER"], expect=200, raw=True)
        s, b = call("GET", path + ("&" if "?" in path else "?") + "format=pdf", t["admin"], expect=200, raw=True)
    s, coll = call("GET", "/reports/collections", t["MANAGER"])
    check(any(i["label"] == "Total recovered" and float(i["value"]) >= 2700 for i in coll["summary"]), "collections total")
    s, dash = call("GET", "/dashboard/staff", t["MANAGER"], expect=200)
    check(float(dash["totalOutstanding"]) >= 2800, "staff dashboard outstanding")
    call("GET", "/dashboard/staff", t["CASHIER"], expect=403)

    # Manual adjustment: manager raises, admin approves, posts to ledger
    s, adj = call("POST", "/adjustments", t["MANAGER"], {"customerId": cid, "adjType": "DEBIT", "amount": 200,
                                                         "reason": "Late entry of 06-11 fill"}, expect=201)
    call("POST", f"/adjustments/{adj['id']}/approve", t["MANAGER"], {"remarks": "self"}, expect=403)
    call("POST", f"/adjustments/{adj['id']}/approve", t["admin"], {"remarks": "ok"}, expect=200)
    check(balance(t["MANAGER"], cid) == 3000.0, "approved debit adjustment adds 200")

    # Overdue rule: an old opening balance makes the customer overdue
    s, c2 = call("POST", "/customers", t["admin"], {
        "profile": {"name": "Smoke Overdue " + uuid.uuid4().hex[:4], "phone": "9876500002", "creditPeriodDays": 15},
        "creditLimit": 100000, "openingBalance": 5000,
        "openingBalanceDate": (datetime.date.today() - datetime.timedelta(days=45)).isoformat()}, expect=201)
    vno = "KA02OD" + str(uuid.uuid4().int % 10000).zfill(4)
    s, v2 = call("POST", f"/customers/{c2['id']}/vehicles", t["admin"], {"vehicleNumber": vno, "defaultFuel": "DIESEL"}, expect=201)
    s, ag = call("GET", "/reports/outstanding?overdueOnly=true", t["MANAGER"], expect=200)
    check(any(r[1] == c2["name"] and float(r[12]) == 5000.0 and float(r[9]) == 5000.0 for r in ag["rows"]),
          "45-day-old opening balance shows as overdue in 31-60 bucket")
    call("PUT", "/settings/OVERDUE_INDENT_ACTION", t["admin"], {"value": "HOLD", "reason": "smoke"}, expect=200)
    s, ind = call("POST", "/indents", t["admin"], {"customerId": c2["id"], "vehicleId": v2["id"], "fuelType": "DIESEL",
                                                    "requestedQuantity": 10}, expect=None)
    check(s in (200, 201) and ind["status"] == "ON_HOLD_CREDIT_LIMIT" and "credit period" in ind["holdReason"],
          f"overdue customer's indent is held ({s})")
    call("PUT", "/settings/OVERDUE_INDENT_ACTION", t["admin"], {"value": "ALLOW", "reason": "smoke reset"}, expect=200)
    call("PUT", "/settings/OVERDUE_INDENT_ACTION", t["admin"], {"value": "MAYBE", "reason": "bad"}, expect=400)
    s, pub = call("GET", "/settings/public", t["CUSTOMER"], expect=200)
    check("PUMP_NAME" in pub and "PUMP_UPI_ID" in pub, "public settings for customers")
    call("GET", "/settings", t["CUSTOMER"], expect=403)


def phase7(t, ctx):
    print("Phase 7: notifications, reminders, backups, audit")
    s, ns = call("GET", "/notification-settings", t["admin"], expect=200)
    check(ns["provider"] == "LOG" and any(e["eventType"] == "PAYMENT_VERIFIED" and e["smsEnabled"] for e in ns["events"]),
          "notification settings with SMS on for PAYMENT_VERIFIED")
    call("GET", "/notification-settings", t["MANAGER"], expect=403)
    s, r = call("POST", "/jobs/reminders", t["admin"], expect=200)
    check(r["customers"] >= 1, f"reminders sent to {r['customers']} customer(s)")
    s, r2 = call("POST", "/jobs/reminders", t["admin"], expect=200)
    check(r2["customers"] == 0, "reminders not repeated within PAYMENT_REMINDER_DAYS")
    s, notes = call("GET", "/notifications", t["CUSTOMER"], expect=200)
    check(any(n["eventType"] == "OUTSTANDING_REMINDER" for n in notes["items"]), "customer sees reminder in app")
    s, dl = call("GET", "/notification-settings/deliveries", t["admin"], expect=200)
    check(any(d["channel"] == "SMS" for d in dl), f"SMS outbox rows queued ({len(dl)})")
    s, b = call("POST", "/backups", t["admin"], expect=200)
    check(b["status"] == "SUCCESS" and b["sizeBytes"] > 0, f"backup run: {b['status']} {b.get('error') or ''}")
    if b["status"] == "SUCCESS":
        s, f = call("GET", f"/backups/{b['id']}/download", t["admin"], expect=200, raw=True)
        check(f[:5] == b"PGDMP", "backup download is a pg_dump file")
    call("POST", "/backups", t["MANAGER"], expect=403)
    s, au = call("GET", "/audit?q=PAYMENT_VERIFIED", t["admin"], expect=200)
    check(len(au) >= 1, "audit log search")
    call("GET", "/audit", t["MANAGER"], expect=403)


if __name__ == "__main__":
    tokens, cid = setup()
    phases = sys.argv[1:] or ["45"]
    ctx = {"cid": cid}
    if "45" in phases:
        ctx["sale"] = phase45(tokens, cid)
    for extra in phases:
        fn = globals().get("phase" + extra)
        if extra != "45" and fn:
            fn(tokens, ctx)
    print("\nFAILURES:" if FAILS else "\nALL PASSED", *FAILS, sep="\n")
    sys.exit(1 if FAILS else 0)
