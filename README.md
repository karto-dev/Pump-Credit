# Petrol Pump Credit Management System

Credit sales and payment recovery for an Indian petrol pump. There are four roles: Customer, Cashier, Manager and Admin.

| Layer     | Tech                                                   |
|-----------|--------------------------------------------------------|
| Backend   | Java 21, Spring Boot 3.3, Spring Security (JWT), JPA   |
| Database  | PostgreSQL 16, schema managed by Flyway                |
| Frontend  | React 18 + TypeScript + Vite (mobile-first, PWA later) |
| Storage   | Local disk in dev, S3 on AWS (bill photos, proofs)     |

## Run locally

```bash
# 1. Database (own container on port 5434, separate from other local Postgres instances)
docker compose up -d

# 2. Backend on :8080. Flyway creates every table on first start.
cd backend && mvn spring-boot:run

# 3. Frontend on :5173 (needs Node 18+; .nvmrc pins 22)
cd frontend && nvm use && npm install && npm run dev
```

Open http://localhost:5173. On an empty database the backend creates the first admin user from
`app.bootstrap.*` in `backend/src/main/resources/application.yml`. Override it with the
`ADMIN_USERNAME` / `ADMIN_PASSWORD` environment variables.

Connect to the database with any client at `localhost:5434`, database `petrol_credit`, user `ppcms`.

## Database design (`backend/src/main/resources/db/migration`)

| Area            | Tables |
|-----------------|--------|
| Access          | `roles`, `permissions`, `role_permissions`, `users`, `user_permission_overrides`, `user_sessions`, `password_reset_tokens` |
| Masters         | `customers`, `vehicles`, `fuel_types`, `fuel_rates` |
| Credit limits   | `credit_limit_changes`, `credit_limit_exceptions` |
| Sales           | `indents`, `credit_transactions`, `bill_attachments`, `customer_approvals`, `disputes` |
| Payments        | `payment_submissions` (customer claim), `cashier_payment_entries`, `payments` (canonical record linking both), `payment_proofs`, `payment_verifications`, `payment_allocations` |
| Ledger          | `adjustments`, `ledger_entries` (append-only), view `v_customer_balances` |
| Notifications   | `notification_settings`, `notifications`, `notification_deliveries` (SMS/WhatsApp outbox) |
| System          | `audit_logs` (append-only), `system_settings`, `backup_runs` |

Rules enforced inside the database, so they hold even if application code has a bug:

- Financial rows (sales, payments, proofs, adjustments, limits, customers) cannot be deleted.
- `ledger_entries`, `audit_logs`, `customer_approvals` and `payment_verifications` are append-only.
- A sale's `amount` must equal `round(quantity × rate, 2)`.
- Only `VERIFIED` payments and `APPROVED` adjustments can be posted to the ledger. An unverified claim never reduces outstanding.
- The same UTR/reference can be verified only once per payment method.
- Maker-checker: the cashier who recorded a payment cannot verify it, and an adjustment's creator cannot approve it.
- A posted sale's customer, vehicle, fuel, quantity, rate, amount, time and cashier cannot be edited. Corrections are adjustments.
- UPI/NEFT/IMPS/RTGS/Cheque payments require a reference number.
- Vehicle numbers are stored normalised (upper case, no spaces) and are unique among active vehicles.
- Confirmed outstanding = Σ ledger debits − Σ ledger credits. That works out to opening balance + sales − verified recoveries + debit adjustments − credit adjustments.

Permissions are data, not code. Admin edits `role_permissions`, or grants or revokes a single permission
for one user through `user_permission_overrides`. The backend reloads permissions on every request.

## Build status

- [x] **Phase 1 (foundation):** full schema, seed roles/permissions/settings, JWT login with server-side sessions,
      lockout after 5 failed attempts, logout, audit logging, role dashboards (placeholders)
- [x] Phase 1 (admin): customers (search, over-limit filter, utilisation), vehicles, credit-limit changes with history,
      admin-only temporary exceptions (max 90 days), users (create with one-time temporary password, deactivate,
      reset, role change), per-user permission overrides, role permission matrix, forced first-login password change
- [x] Phase 2: customer indents (litres / amount / full tank), over-limit indents held until admin authorises
      (optionally granting an exception), cashier queue (accept / reject / fill), credit sale with bill photo
      (server-computed amount, ledger posting, duplicate and re-used-photo detection), sales register with
      bill viewer, fuel rates, in-app notifications, live customer and cashier dashboards
- [x] Phase 3: customer approve / reject on the bill viewer (staff, including admin, cannot answer for a customer),
      unanswered bills move to NO_RESPONSE after `CUSTOMER_APPROVAL_TIMEOUT_HRS` (scheduled job, late answers still accepted),
      rejection opens a dispute in the manager's queue, resolution as bill upheld / partial credit / full reversal,
      credit and reversal go through a maker-checker adjustment that posts to the ledger only when approved,
      posted sales frozen by a DB trigger, live approval and dispute tiles on manager/admin dashboards
- [x] Phase 4: customer payment claims (UPI / NEFT / IMPS / RTGS / cheque / cash, with screenshot) showing the pump's
      UPI ID and bank details; cashier records money received, optionally against the customer's open claim;
      duplicate warnings for a re-used UTR, a re-used screenshot or the same cash amount on the same day;
      customer can withdraw a claim until the cashier has taken the money; more proof can be added any time
- [x] Phase 5: verifier (manager / admin) verifies against the bank statement or cash drawer, can credit less
      than claimed, ask for more proof, reject or mark a duplicate. Verification posts to the ledger in the same
      transaction and allocates the money FIFO to the oldest unpaid sales. A cash payment cannot be verified before a
      cashier has recorded it, the recorder cannot verify, a reference can be verified only once, and a verified
      payment is frozen by a DB trigger
- [x] Phase 6: statement of account for any period (customer sees their own) with opening / closing balance and
      overdue; reports: outstanding and ageing (0-30 / 31-60 / 61-90 / 90+ and overdue past the credit period),
      credit sales by day / customer / vehicle / fuel / cashier or every sale, verified recoveries by method,
      cashier day book. Every report and statement downloads as Excel or PDF (audited). Credit controls: optional
      hold or block of indents for customers with overdue dues (`OVERDUE_INDENT_ACTION`), hourly expiry of
      temporary limits. Manual debit / credit adjustments with maker-checker approval. Live manager / admin dashboard
- [x] Phase 7: SMS / WhatsApp outbox per event (admin switches channels and edits message text), delivery worker
      with retries (`NOTIFY_PROVIDER=LOG` until a provider is chosen, `WEBHOOK` to send), daily outstanding reminders,
      nightly `pg_dump` backups with download and retention, admin settings screen, audit log viewer, S3 storage
      for bills / proofs / backups, Docker images, production compose with Caddy HTTPS, EC2 setup and deploy
      scripts, GitHub Actions workflow, installable PWA manifest

## Day-to-day notes

- **Backups on a laptop:** pg_dump is not installed on macOS here, so run the backend with
  `BACKUP_COMMAND="docker exec ppcms-postgres pg_dump -U ppcms --format=custom petrol_credit"` for
  Admin → Backups → "Back up now" to work. In Docker the backend image already has pg_dump 16.
- **SMS / WhatsApp:** with `NOTIFY_PROVIDER=LOG` (default) messages only go to the server log and the
  Admin → Notifications delivery list. `WEBHOOK` POSTs `{channel, to, text}` JSON to `NOTIFY_WEBHOOK_URL`
  (bearer `NOTIFY_WEBHOOK_TOKEN`) - point it at the provider's HTTP API or a tiny relay.
- **Pay screen:** set `PUMP_NAME`, `PUMP_UPI_ID` and `PUMP_BANK_DETAILS` in Admin → Settings.
- **Smoke test:** `scripts/smoke-test.py` drives payments, reports, adjustments, reminders and backups
  through the API. It creates its own customers and users, so run it against a scratch database
  (see the script header), never production.

## Deploy files

| File | Purpose |
|------|---------|
| `backend/Dockerfile`, `frontend/Dockerfile` | Images: Spring Boot on JRE 21 (with pg_dump 16); SPA served by Caddy |
| `docker-compose.prod.yml`, `.env.prod.example` | Postgres + backend + Caddy on one host; only 80/443 exposed |
| `deploy/Caddyfile` | HTTPS (Let's Encrypt), `/api` proxy, security headers |
| `deploy/setup-ec2.sh` | One-time Docker + compose + swap on Amazon Linux 2023 |
| `deploy/deploy.sh` | Pull, build, restart, wait for the backend |
| `.github/workflows/deploy.yml` | Build on every push / PR; deploy `main` over SSH |

## Free hosting for testing

Render (app) + Neon (database), both free: see [docs/DEPLOY-RENDER.md](docs/DEPLOY-RENDER.md) and `render.yaml`.

## Deploying to AWS without Jenkins

> AWS changed its free tier in July 2025. Accounts created after that date get a **Free plan with
> credits (about $100–200, valid 6 months)** instead of the old 12-month free tier. Both setups below
> fit inside those credits. Check your account's billing console and set a **budget alert ($5)** first.

### Option A: one EC2 instance (cheapest, recommended to start)

```
Internet ── EC2 t4g.small / t3.micro (Docker Compose)
              ├─ Caddy (HTTPS via Let's Encrypt, serves frontend/dist, proxies /api)
              ├─ backend container (Spring Boot)
              └─ postgres container (EBS volume, nightly pg_dump → S3)
            S3 bucket (private): bill photos and payment proofs
```

1. Launch an Amazon Linux 2023 instance and attach an Elastic IP. In the security group, open only 22 (your IP), 80 and 443.
2. Install Docker and the compose plugin, then copy the repo onto the instance.
3. Point a domain (or a free DuckDNS subdomain) at the Elastic IP. Caddy obtains the HTTPS certificate automatically.
4. Give the instance an IAM role with access to the S3 bucket. Don't put access keys on the box.
5. Add a cron job that runs `pg_dump` and copies the dump to S3 every night.

### Option B: managed services (once it's live)

RDS PostgreSQL `db.t4g.micro` (automated backups), the backend on EC2 or App Runner,
the frontend on S3 + CloudFront, and bill images on S3. The cost is higher but backups and patching are handled for you.

### CI/CD without Jenkins

Use **GitHub Actions**, which is free for this volume. On a push to `main` it builds the jar and the frontend,
then deploys over SSH (`docker compose pull && up -d`). A `deploy.sh` script you run by hand works just as well at the start.
