const { uuid } = require('../util');
const store = require('../db/store');

/**
 * Upsert client rounds by roundUuid (client-generated). Idempotent sync.
 */
function sync(userId, rounds, trackSamples = []) {
  if (!Array.isArray(rounds)) {
    const err = new Error('rounds_array_required');
    err.status = 400;
    throw err;
  }
  return store.update((data) => {
    let inserted = 0;
    let updated = 0;
    for (const r of rounds) {
      const uuidKey = r.roundUuid || r.uuid;
      if (!uuidKey) continue;
      const existing = data.rounds.find(
        (x) => x.userId === userId && x.roundUuid === uuidKey
      );
      const row = {
        id: existing ? existing.id : uuid(),
        userId,
        roundUuid: uuidKey,
        payload: r,
        syncedAt: new Date().toISOString()
      };
      if (existing) {
        Object.assign(existing, row);
        updated++;
      } else {
        data.rounds.push(row);
        inserted++;
      }
    }
    // optional track samples: [{ roundUuid, timeMs, x1, x2, x3 }]
    if (!data.track) data.track = [];
    let trackInserted = 0;
    if (Array.isArray(trackSamples)) {
      for (const s of trackSamples) {
        if (!s.roundUuid || s.timeMs == null) continue;
        const key = `${userId}:${s.roundUuid}:${s.timeMs}`;
        if (data.track.some((t) => t.key === key)) continue;
        data.track.push({ key, userId, ...s });
        trackInserted++;
      }
    }
    return { inserted, updated, trackInserted, total: data.rounds.filter((r) => r.userId === userId).length };
  });
}

function listForUser(userId, limit = 200) {
  const data = store.read();
  return data.rounds
    .filter((r) => r.userId === userId)
    .sort((a, b) => (b.syncedAt || '').localeCompare(a.syncedAt || ''))
    .slice(0, limit);
}

function adminStats() {
  const data = store.read();
  return {
    users: data.users.length,
    rounds: data.rounds.length,
    trackPoints: (data.track || []).length,
    activeLeases: data.leases.filter(
      (l) => !l.revoked && new Date(l.expiresAt).getTime() > Date.now()
    ).length,
    codes: data.codes.length
  };
}

module.exports = { sync, listForUser, adminStats };
