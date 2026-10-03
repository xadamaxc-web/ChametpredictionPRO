const { uuid } = require('../util');
const store = require('../db/store');
const config = require('../config');

function activeLease(userId) {
  const data = store.read();
  const now = Date.now();
  return data.leases.find(
    (l) => l.userId === userId && new Date(l.expiresAt).getTime() > now && !l.revoked
  ) || null;
}

function requestLease(userId) {
  return store.update((data) => {
    const now = Date.now();
    // expire old
    data.leases.forEach((l) => {
      if (l.userId === userId && new Date(l.expiresAt).getTime() <= now) l.revoked = true;
    });
    const existing = data.leases.find(
      (l) => l.userId === userId && new Date(l.expiresAt).getTime() > now && !l.revoked
    );
    if (existing) return existing;

    const lease = {
      id: uuid(),
      userId,
      issuedAt: new Date(now).toISOString(),
      expiresAt: new Date(now + config.leaseHours * 3600 * 1000).toISOString(),
      revoked: false
    };
    data.leases.push(lease);
    data.audit.push({ at: lease.issuedAt, type: 'lease', userId, leaseId: lease.id });
    return lease;
  });
}

function status(userId) {
  const lease = activeLease(userId);
  if (!lease) return { active: false, lease: null };
  return {
    active: true,
    lease,
    remainingMs: new Date(lease.expiresAt).getTime() - Date.now()
  };
}

function revoke(leaseId) {
  return store.update((data) => {
    const l = data.leases.find((x) => x.id === leaseId);
    if (!l) return null;
    l.revoked = true;
    return l;
  });
}

module.exports = { requestLease, status, activeLease, revoke };
