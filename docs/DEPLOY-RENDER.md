# Free hosting on Render + Neon

The result is a public link (e.g. `https://pump-credit.onrender.com`) that anyone can open in a browser. Nothing needs installing on the tester's side.

```
Browser ──► Render static site (React)  ──/api/*──►  Render web service (Spring Boot, Docker)
                                                          │
                                                   Neon PostgreSQL (free)
```

**Free-tier limits**
- The backend sleeps after 15 minutes without visitors. The first visit after that takes about a minute.
- Bill photos and payment proofs are kept on the container's disk and are lost on every restart or redeploy. For real use, switch to S3 (`STORAGE_TYPE=S3`).
- Neon's free database is 0.5 GB.

## 1. Push the code to GitHub

1. On GitHub, click **New repository**. Name it `pump-credit`, make it **Private**, and leave README, .gitignore and licence unticked.
2. In this folder, run:
   ```bash
   git init -b main
   git add .
   git commit -m "Petrol pump credit management"
   git remote add origin https://github.com/<your-user>/pump-credit.git
   git push -u origin main
   ```

## 2. Create the database on Neon

1. Sign up at https://neon.tech (you can use your GitHub login). Create a project: Postgres 16, region **Asia Pacific (Singapore)**.
2. On the dashboard, click **Connect**. Turn **Connection pooling off** (Flyway and the scheduled jobs need a direct connection), then copy:
   - the host, e.g. `ep-cool-name-123456.ap-southeast-1.aws.neon.tech`
   - the database (`neondb`), the user (`neondb_owner`) and the password
3. Build the JDBC URL from those values:
   `jdbc:postgresql://<host>/neondb?sslmode=require`

The backend creates every table itself on first start (Flyway).

## 3. Deploy on Render

1. Sign up at https://render.com with GitHub and allow it access to the `pump-credit` repo.
2. Go to **New → Blueprint**, choose the repo, and Render reads `render.yaml`.
3. Fill in the values it asks for:

   | Key | Value |
   |-----|-------|
   | `DB_URL` | the JDBC URL from step 2 |
   | `DB_USERNAME` | `neondb_owner` |
   | `DB_PASSWORD` | the Neon password |
   | `ADMIN_PASSWORD` | a temporary admin password (you change it at first login) |

4. Click **Apply**. The backend's first build takes about 5–8 minutes; the frontend's about 1–2.
5. Check the two URLs Render assigned:
   - If the backend is not `https://pump-credit-api.onrender.com`, change the `/api/*` destination in `render.yaml` and push.
   - If the frontend is not `https://pump-credit.onrender.com`, set the backend's `CORS_ORIGINS` env var (Render → pump-credit-api → Environment) to the real frontend URL.

## 4. Give your friend access

1. Open the frontend URL. Log in as `admin` with the `ADMIN_PASSWORD` you set, then choose a new password.
2. Admin → **Settings**: set the pump name, UPI ID and bank details.
3. **Fuel rates**: enter today's petrol and diesel rates.
4. **Customers**: add one customer with a vehicle and a credit limit.
5. **Users**: create logins for your friend, for example a Manager, a Cashier and a Customer linked to that customer. Each one gets a one-time password that must be changed at first login.
6. Send your friend the link and the logins.

## Updating

Every push to `main` redeploys both services automatically.

## Troubleshooting

- **Login shows "Request failed (502/504)"**: the backend is waking up. Wait a minute and try again.
- **Login shows 403**: `CORS_ORIGINS` doesn't match the frontend URL exactly (it must start with `https://` and have no trailing slash).
- **The backend fails at start with a connection error**: check that `DB_URL` starts with `jdbc:postgresql://`, ends with `?sslmode=require`, and uses the non-pooler host.
- **Signed out after 10 minutes**: that is the idle timeout. Change it with the backend env var `SESSION_IDLE_MINUTES`.
- **Login shows "The server is taking too long to respond"**: the backend did not answer within 75 seconds (usually a cold start). Try again.
- **Logs**: Render → service → **Logs**.
