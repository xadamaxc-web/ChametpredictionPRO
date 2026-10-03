# Chamet Guesser Server — Phase 2 (8.2.0)

Node.js control plane for closed-group distribution.

## Features (Sessions 11–16)
- Auth (register / login, JWT)
- Round + track sync
- **1-hour engine lease** (client checks before unlocking when server mode is on)
- Free / Pro **daily quota**
- **Payment codes** (redeem → Pro)
- **Admin panel** at `/`

## Run
```bash
cd server
npm install
npm run seed    # optional demo users + pro code
npm start       # :8787
npm test
```

Env: `PORT`, `JWT_SECRET`, `LEASE_HOURS`, `FREE_DAILY_QUOTA`, `PRO_DAILY_QUOTA`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `CHAMET_DATA_DIR`.

Default admin (bootstrap): `admin@local` / `admin-change-me`.

## Android
Settings → Server: enable, URL (`http://10.0.2.2:8787` for emulator), login, request lease, redeem code.
Offline Phase 1 still works with server **disabled**.


## Phase 3
- `node scripts/nightly.js` — cron nightly learning
- Landing: `http://localhost:8787/`
- Admin: `http://localhost:8787/admin`
- Public API: `/api/public/live`, `/api/public/waitlist`, `/api/public/launch`
- Referrals: `GET /api/referrals/code`, `POST /api/referrals/apply`
