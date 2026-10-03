const store = require('../db/store');
const config = require('../config');

function today() {
  return new Date().toISOString().slice(0, 10);
}

function effectivePlan(user) {
  if (user.planExpiresAt) {
    if (new Date(user.planExpiresAt).getTime() < Date.now()) return 'free';
  }
  return user.plan || 'free';
}

function limitFor(plan, user) {
  if (plan === 'pro') {
    if (config.proDailyQuota < 0) return Number.MAX_SAFE_INTEGER;
    return config.proDailyQuota + (user && user.bonusQuota ? Number(user.bonusQuota) : 0);
  }
  const bonus = (user && user.bonusQuota) ? Number(user.bonusQuota) : 0;
  return config.freeDailyQuota + bonus;
}

function getQuota(user) {
  const d = today();
  const plan = effectivePlan(user);
  const count = user.daily && user.daily.date === d ? user.daily.count : 0;
  const limit = limitFor(plan, user);
  return {
    plan,
    date: d,
    used: count,
    limit: limit === Number.MAX_SAFE_INTEGER ? null : limit,
    remaining: limit === Number.MAX_SAFE_INTEGER ? null : Math.max(0, limit - count),
    planExpiresAt: user.planExpiresAt || null
  };
}

function consume(userId) {
  return store.update((data) => {
    const u = data.users.find((x) => x.id === userId);
    if (!u) {
      const err = new Error('not_found');
      err.status = 404;
      throw err;
    }
    // demote expired pro
    if (u.plan === 'pro' && u.planExpiresAt && new Date(u.planExpiresAt).getTime() < Date.now()) {
      u.plan = 'free';
    }
    const d = today();
    if (!u.daily || u.daily.date !== d) u.daily = { date: d, count: 0 };
    const limit = limitFor(effectivePlan(u), u);
    if (u.daily.count >= limit) {
      const err = new Error('quota_exceeded');
      err.status = 429;
      throw err;
    }
    u.daily.count += 1;
    return getQuota(u);
  });
}

module.exports = { getQuota, consume, limitFor, effectivePlan };
