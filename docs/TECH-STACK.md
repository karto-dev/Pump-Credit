# Pump Credit: tech stack

This document covers every technology used in the project, what it is used for, and where it lives in the code.

```
 Phone / laptop browser
        │  HTTPS
        ▼
 ┌──────────────────────┐   /api/*   ┌──────────────────────────┐   JDBC (SSL)   ┌──────────────────┐
 │ Frontend             │ ─────────► │ Backend                  │ ─────────────► │ PostgreSQL 16    │
 │ React + Bootstrap    │            │ Java 21 + Spring Boot 3  │                │ (Docker / Neon)  │
 │ (static files)       │ ◄───────── │ REST API, JWT security   │ ◄───────────── │ schema by Flyway │
 └──────────────────────┘    JSON    └──────────────────────────┘                └──────────────────┘
                                          │            │
                                bill photos / proofs   SMS / WhatsApp outbox
                                (local disk or S3)     (log or webhook)
```

---

## 1. Backend (`backend/`)

| Technology | Version | Used for |
|---|---|---|
| **Java** | 21 (Temurin) | Language. Records keep the data classes short. |
| **Spring Boot** | 3.3.5 | Application framework: web server, configuration, scheduling and dependency injection. |
| **Spring Web (MVC)** | via Boot | REST controllers under `/api/...` (one controller per module). |
| **Spring Security** | via Boot | Login, role/permission checks (`@PreAuthorize`) and CORS. |
| **JJWT** | 0.12.6 | Signs and reads the login token (JWT). Each token is tied to a row in `user_sessions`, so logout and admin revoke work immediately. |
| **Spring JDBC (JdbcTemplate)** | via Boot | Most database queries: plain SQL mapped to Java records. |
| **Spring Data JPA / Hibernate** | 6.5 | Only for `User` / `Role` entities. Hibernate validates the schema and never changes it. |
| **Bean Validation** | via Boot | Checks on request fields (required, length, mobile/GSTIN/PAN patterns). |
| **Flyway** | via Boot | Database migrations `V1`–`V7` in `src/main/resources/db/migration`. Creates every table, trigger and seed row on first start. |
| **PostgreSQL JDBC driver** | 42.7.4 | Connects to PostgreSQL. |
| **Apache POI** (`poi-ooxml`) | 5.3.0 | Excel (`.xlsx`) export of reports and statements. |
| **OpenPDF** | 1.4.2 | PDF export of reports and statements (Indian digit grouping, ₹). |
| **AWS SDK v2 (S3)** | 2.28.29 | Optional storage of bill photos, payment proofs and backups in an S3 bucket (`STORAGE_TYPE=S3`). |
| **Spring Boot Actuator** | via Boot | `/actuator/health`, used by Render and Docker health checks. |
| **Spring Scheduling** | via Boot | Background jobs: bill-approval timeout, limit-exception expiry, daily reminders, nightly backup, SMS delivery worker. |
| **PostgreSQL advisory locks** | n/a | Stop a scheduled job from running twice if two backend copies are up. |
| **pg_dump** | 16 | Database backups (Admin → Backups). |
| **Maven** | 3.9 | Build tool (`pom.xml`). Output is a single runnable jar. |

### Backend modules (`com.petrolpump.credit.*`)

| Package | What it does |
|---|---|
| `auth`, `security`, `config` | Login and logout, JWT filter, lockout after 5 failed logins, forced password change, CORS. |
| `user`, `admin`, `bootstrap` | Users, roles and permissions (stored as data), per-user permission overrides, first-admin creation. |
| `customer` | Customers and vehicles. |
| `credit` | Credit limits, temporary exceptions, overdue and ageing (0–30/31–60/61–90/90+). |
| `fuel` | Fuel rates. |
| `indent` | Customer fuel requests, held when over the limit or overdue. |
| `sale` | Credit sales with bill photo; amount computed on the server. |
| `approval` | Customer approves or rejects each bill; no-response timeout. |
| `dispute` | Rejected bills: uphold, partial credit or reversal. |
| `adjustment` | Manual debit and credit with maker-checker approval. |
| `payment` | Payment claims, cashier entries, verification, FIFO allocation, duplicate detection. |
| `ledger` | The only writer of the append-only customer ledger. |
| `report` | Statement, outstanding, sales, collections and cashier day book, with Excel and PDF. |
| `notification` | In-app bell, plus the SMS/WhatsApp outbox and delivery worker. |
| `audit` | Audit log of every sensitive action. |
| `backup`, `jobs`, `storage`, `settings`, `common` | Backups, scheduled jobs, file storage, admin settings, error handling. |

---

## 2. Database

| Technology | Used for |
|---|---|
| **PostgreSQL 16** | Main database. |
| **Triggers and constraints** | The money rules live in the database, so they hold even if application code has a bug: no deleting financial rows, append-only ledger and audit, verified payments frozen, a reference number verified only once, sale amount = qty × rate, maker-checker. |
| **View `v_customer_balances`** | Confirmed outstanding per customer, from the ledger. |

Where it runs:
- **Local:** Docker container `ppcms-postgres` on port **5434** (`docker-compose.yml`), database `petrol_credit`.
- **Cloud (testing):** **Neon** free Postgres (Singapore).
- **Production option:** Postgres container on EC2 (`docker-compose.prod.yml`) or AWS RDS.

---

## 3. Frontend (`frontend/`)

| Technology | Version | Used for |
|---|---|---|
| **React** | 18.3.1 | UI library, with one page component per screen in `src/pages`. |
| **TypeScript** | 5.6.3 | Type safety. API types are in `src/api/types.ts`. |
| **React Router** | 6.28.0 | Page navigation and permission-based route guards (`src/main.tsx`). |
| **Bootstrap** | 5.3.3 | Base styling: reboot, modal, spinner and utility classes. |
| **Bootstrap Icons** | 1.11.3 | All icons (sidebar, tiles, buttons). |
| **Custom theme** | n/a | `src/styles.css`: green brand theme, sidebar layout, cards, tables, badges, mobile layout. |
| **Vite** | 5.4.11 | Dev server (`npm run dev`, port 5173, proxies `/api` to the backend) and production build. |
| **PWA manifest** | n/a | `public/manifest.webmanifest`, so the app can be added to a phone home screen. |
| **Node.js** | 22 (`.nvmrc`) | Needed only to build or run the frontend locally. |

The frontend calls the backend at the relative path `/api`, so the same build works locally (Vite proxy), on Render (rewrite rule) and on EC2 (Caddy proxy).

---

## 4. Hosting and DevOps

| Technology | Used for | Files |
|---|---|---|
| **Docker** | Backend image (JRE 21 + pg_dump 16) and frontend image (Caddy serving the built site). | `backend/Dockerfile`, `frontend/Dockerfile` |
| **Docker Compose** | Local database; full production stack on one server. | `docker-compose.yml`, `docker-compose.prod.yml` |
| **GitHub** | Source code: `github.com/karto-dev/Pump-Credit`. | n/a |
| **Render** (free) | Hosts the backend (Docker web service) and the frontend (static site). Redeploys on every push to `main`. | `render.yaml`, `docs/DEPLOY-RENDER.md` |
| **Neon** (free) | Cloud PostgreSQL for the Render deployment. | n/a |
| **Caddy** | Production option: web server with automatic HTTPS (Let's Encrypt), serves the frontend, proxies `/api`. | `deploy/Caddyfile` |
| **AWS EC2 / S3** | Production option: one EC2 server running compose; S3 for files and backups. | `deploy/setup-ec2.sh`, `deploy/deploy.sh` |
| **GitHub Actions** | Optional CI (build check, EC2 deploy). Kept locally in `.github/`; not pushed yet (needs a token with `workflow` scope). | `.github/workflows/deploy.yml` |

---

## 5. Configuration (environment variables)

| Variable | Purpose | Default (local) |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Database connection | local Docker DB on 5434 |
| `JWT_SECRET` | Signs login tokens (32+ characters in any shared environment) | dev value |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | First admin, created only on an empty database | `admin` / `Admin@12345` |
| `CORS_ORIGINS` | Frontend URL(s) allowed to call the API | `http://localhost:5173` |
| `PORT` | Server port (set by Render) | 8080 |
| `STORAGE_TYPE`, `STORAGE_LOCAL_ROOT`, `S3_BUCKET`, `AWS_REGION` | Where photos and proofs are stored | `LOCAL`, `./uploads` |
| `NOTIFY_PROVIDER`, `NOTIFY_WEBHOOK_URL`, `NOTIFY_WEBHOOK_TOKEN` | SMS/WhatsApp sending (`LOG` or `WEBHOOK`) | `LOG` |
| `BACKUP_ENABLED`, `BACKUP_CRON`, `BACKUP_DIR`, `BACKUP_COMMAND` | Nightly backups | off, `./backups` |
| `JAVA_OPTS` | JVM memory settings (tuned for Render's 512 MB) | n/a |

Business settings (pump name, UPI ID, bank details, overdue rules, reminder days, approval timeout) are edited in the app under **Admin → Settings**.

---

## 6. Developer tools

| Tool | Used for |
|---|---|
| **IntelliJ IDEA / VS Code** | Editing (any IDE works). |
| **nvm** | Switching to Node 22 for the frontend. |
| **Python 3** | `scripts/smoke-test.py`, an end-to-end API test. Run it only against a scratch database. |
| **psql / any SQL client** | Inspecting the database (`localhost:5434`, user `ppcms`). |

## 7. Running locally

```bash
docker compose up -d                                   # database on :5434
cd backend && mvn spring-boot:run                      # API on :8080
cd frontend && nvm use && npm install && npm run dev   # UI on :5173
```

To run the backend on another port: `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8090`, then start the frontend with `API_PROXY=http://localhost:8090 npm run dev`.
