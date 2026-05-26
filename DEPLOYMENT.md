# ZonePilot — Production Deployment Guide

**Architecture:** Frontend → Vercel | Backend → Render | Database → Fly.io (PostGIS + pgRouting)

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| flyctl | latest | https://fly.io/docs/hands-on/install-flyctl/ |
| Vercel CLI | latest | `npm i -g vercel` |
| Git | any | — |
| Java 25 (local build test only) | 25 | https://adoptium.net |

Accounts required: Fly.io, Render, Vercel (all have free tiers).

---

## Step 1 — Deploy the Database (Fly.io)

The database must be deployed first because Render needs the connection string.

### 1.1 Create the Fly.io app

```bash
cd fly/
fly auth login
fly apps create zonepilot-db --machines
```

### 1.2 Create the persistent volume (10 GB)

```bash
fly volumes create zonepilot_pgdata --app zonepilot-db --region sin --size 10
```

> Region `sin` = Singapore. Closest to Bangalore. Change to `bom` (Mumbai) if it becomes available.

### 1.3 Set database credentials as secrets

```bash
fly secrets set \
  POSTGRES_USER=zonepilot \
  POSTGRES_PASSWORD=<strong-random-password> \
  --app zonepilot-db
```

> Generate a strong password: `openssl rand -base64 32`

### 1.4 Deploy the custom PostGIS+pgRouting image

```bash
fly deploy --app zonepilot-db
```

This builds `fly/Dockerfile` (PostGIS 16-3.4 + pgRouting + osm2pgrouting) and runs `init-db.sh` on first boot to install extensions.

### 1.5 Verify extensions are installed

```bash
fly ssh console --app zonepilot-db --command \
  "psql -U zonepilot -d ZonePilot -c '\dx'"
```

Expected output includes `postgis` and `pgrouting` in the extension list.

### 1.6 Get the connection string

Fly.io Postgres is accessible internally via:
```
jdbc:postgresql://<app-name>.internal:5432/ZonePilot?sslmode=disable
```

For external access (Render → Fly.io), allocate a public IP:
```bash
fly ips allocate-v4 --app zonepilot-db
fly ips allocate-v6 --app zonepilot-db
```

Then the external connection string is:
```
jdbc:postgresql://<app-name>.fly.dev:5432/ZonePilot?sslmode=require
```

> **Note:** Fly.io apps in the same organization can connect internally without SSL using `.internal` hostnames. If Render and Fly.io are in different networks (they are), use the public hostname with `sslmode=require`.

---

## Step 2 — Deploy the Backend (Render)

### 2.1 Connect your repository

1. Go to https://dashboard.render.com → New → Web Service
2. Connect your GitHub/GitLab repository
3. Render will detect `render.yaml` automatically

### 2.2 Set environment variables in Render dashboard

Navigate to your service → Environment → Add the following:

| Variable | Value | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://zonepilot-db.fly.dev:5432/ZonePilot?sslmode=require` | From Step 1.6 |
| `SPRING_DATASOURCE_USERNAME` | `zonepilot` | Must match Fly.io secret |
| `SPRING_DATASOURCE_PASSWORD` | `<your-password>` | Must match Fly.io secret |
| `CORS_ALLOWED_ORIGINS` | `https://your-app.vercel.app` | Set after Vercel deploy in Step 3 |
| `GOOGLE_ROUTES_API_KEY` | `<key>` | Optional — enables predictive compliance |

> `SPRING_PROFILES_ACTIVE=prod` is already set in `render.yaml`.

### 2.3 Build and start commands

These are handled by `render.yaml` (Docker runtime). Render builds the `backend/Dockerfile` automatically.

- **Build:** Docker multi-stage build (Maven → JRE)
- **Start:** `java -jar app.jar` (from Dockerfile ENTRYPOINT)
- **Health check:** `GET /v3/api-docs` → HTTP 200

### 2.4 Verify backend is running

```bash
curl https://your-backend.onrender.com/v3/api-docs | head -c 200
curl https://your-backend.onrender.com/api/vehicles
```

Expected: JSON response with `"success": true`.

### 2.5 Verify Flyway migrations ran

Check Render logs for:
```
Successfully applied 18 migrations to schema "public"
```

---

## Step 3 — Deploy the Frontend (Vercel)

### 3.1 Deploy via Vercel CLI

```bash
cd /path/to/ZonePilot
vercel --prod
```

Or connect via Vercel dashboard → Import Git Repository.

### 3.2 Set environment variables in Vercel

In Vercel dashboard → Project → Settings → Environment Variables:

| Variable | Value | Environment |
|---|---|---|
| `VITE_API_BASE_URL` | `https://your-backend.onrender.com` | Production |

> Do NOT include a trailing slash. The frontend appends `/api/...` automatically.

### 3.3 Redeploy after setting env vars

```bash
vercel --prod
```

Or trigger a redeploy from the Vercel dashboard.

### 3.4 Update CORS on Render

Now that you have the Vercel URL, update `CORS_ALLOWED_ORIGINS` on Render:
```
https://your-app.vercel.app
```

Render will restart the service automatically.

---

## Step 4 — Import OSM Road Network

Route validation requires the Bangalore road network. Without it, `/api/routes/validate` returns HTTP 503.

```bash
# From project root — bangalore.osm.pbf must exist (43 MB clipped file)
./fly/import-osm.sh
```

If you only have the full Karnataka file:
```bash
osmium extract --bbox=77.45,12.83,77.78,13.14 karnataka-latest.osm.pbf -o bangalore.osm.pbf
./fly/import-osm.sh
```

The import takes ~5–10 minutes. Verify:
```bash
fly ssh console --app zonepilot-db --command \
  "psql -U zonepilot -d ZonePilot -c 'SELECT COUNT(*) FROM blr_2po_4pgr;'"
```

Expected: > 100,000 road segments.

---

## Environment Variables Reference

### Backend (Render)

| Variable | Required | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Yes | `prod` |
| `SPRING_DATASOURCE_URL` | Yes | Fly.io Postgres JDBC URL with `?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Yes | Postgres username |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Postgres password |
| `CORS_ALLOWED_ORIGINS` | Yes | Vercel frontend URL, e.g. `https://zonepilot.vercel.app` |
| `GOOGLE_ROUTES_API_KEY` | No | Enables Google Routes API for predictive compliance |
| `PORT` | Auto | Injected by Render; Spring binds to `${PORT:8080}` |

### Frontend (Vercel)

| Variable | Required | Description |
|---|---|---|
| `VITE_API_BASE_URL` | Yes | Render backend URL, e.g. `https://zonepilot-backend.onrender.com` |

### Database (Fly.io secrets)

| Secret | Description |
|---|---|
| `POSTGRES_USER` | Database username |
| `POSTGRES_PASSWORD` | Database password |

---

## Verification Checklist

Run these checks after full deployment:

```bash
BACKEND=https://your-backend.onrender.com
FRONTEND=https://your-app.vercel.app

# 1. Frontend loads
curl -s -o /dev/null -w "%{http_code}" $FRONTEND
# Expected: 200

# 2. Backend OpenAPI spec
curl -s $BACKEND/v3/api-docs | python3 -m json.tool | head -5
# Expected: valid JSON with "openapi" key

# 3. Vehicles endpoint
curl -s $BACKEND/api/vehicles | python3 -m json.tool
# Expected: {"success":true,"data":[...]}

# 4. Zones endpoint
curl -s $BACKEND/api/zones | python3 -m json.tool
# Expected: {"success":true,"data":[...]}

# 5. CORS preflight (replace with your Vercel URL)
curl -s -X OPTIONS $BACKEND/api/vehicles \
  -H "Origin: $FRONTEND" \
  -H "Access-Control-Request-Method: GET" \
  -v 2>&1 | grep -i "access-control"
# Expected: Access-Control-Allow-Origin: <your-vercel-url>

# 6. Route validation (requires OSM import)
curl -s -X POST $BACKEND/api/routes/validate \
  -H "Content-Type: application/json" \
  -d '{"vehicleId":4,"originLat":12.91,"originLng":77.59,"destLat":12.97,"destLng":77.64}'
# Expected: {"success":true,"data":{"compliant":...}}
# If OSM not imported: {"success":false,"error":{"code":"ROAD_NETWORK_UNAVAILABLE"}}

# 7. Simulation state
curl -s $BACKEND/api/simulation/state
# Expected: {"success":true,"data":[]}  (empty before simulation start)

# 8. Reports
curl -s $BACKEND/api/reports/summary
# Expected: {"success":true,"data":[...]}

# 9. PostGIS spatial query (via breach endpoint)
curl -s "$BACKEND/api/breaches?unacknowledged=true"
# Expected: {"success":true,"data":[...]}

# 10. Active restrictions
curl -s $BACKEND/api/reports/active-restrictions
# Expected: {"success":true,"data":[...]}
```

---

## Troubleshooting

### Backend fails to start on Render

**Symptom:** Render shows "Deploy failed" or health check times out.

**Check:**
1. Render logs → look for `HikariPool` connection errors or Flyway migration failures
2. Verify `SPRING_DATASOURCE_URL` includes `?sslmode=require`
3. Verify Fly.io app is running: `fly status --app zonepilot-db`
4. Verify Fly.io has a public IP: `fly ips list --app zonepilot-db`

**Render free tier cold starts:** The first request after 15 minutes of inactivity takes ~30s. This is expected on the free tier.

### CORS errors in browser

**Symptom:** `Access to fetch at '...' from origin '...' has been blocked by CORS policy`

**Fix:**
1. Verify `CORS_ALLOWED_ORIGINS` on Render exactly matches your Vercel URL (no trailing slash)
2. Verify the backend restarted after the env var change
3. Test with the curl CORS preflight check above

### Route validation returns 503

**Symptom:** `{"code":"ROAD_NETWORK_UNAVAILABLE"}`

**Fix:** Run `./fly/import-osm.sh` to import the OSM road network.

### Frontend shows blank page or 404 on refresh

**Symptom:** Direct navigation to `/vehicles` or `/zones` returns 404.

**Fix:** Verify `vercel.json` is in the project root and contains the SPA rewrite rule. Redeploy.

### Flyway migration fails

**Symptom:** `FlywayException: Found non-empty schema(s) "public" without schema history table`

**Fix:** `baseline-on-migrate: true` is set in `application.yml` — this should handle existing schemas. If it persists, check that the database user has `CREATE` privileges.

---

## Cost Estimates (Monthly)

| Service | Tier | Estimated Cost |
|---|---|---|
| Vercel | Hobby (free) | $0 |
| Render | Free web service | $0 (sleeps after 15 min inactivity) |
| Render | Starter ($7/mo) | $7 (always-on, recommended for demos) |
| Fly.io | shared-cpu-1x + 10GB volume | ~$3–5/mo |
| **Total (free tier)** | | **~$3–5/mo** |
| **Total (always-on)** | | **~$10–12/mo** |

---

## Security Notes

- All credentials are environment variables — never committed to git
- `.env` is in `.gitignore`
- Fly.io Postgres is not publicly accessible by default; public IP must be explicitly allocated
- CORS is locked to the specific Vercel domain
- All endpoints are currently unauthenticated — add Spring Security before exposing to untrusted users
- Render provides automatic HTTPS; Vercel provides automatic HTTPS
