module.exports = {
  port: Number(process.env.PORT || 8787),
  jwtSecret: process.env.JWT_SECRET || 'chamet-dev-secret-change-me',
  leaseHours: Number(process.env.LEASE_HOURS || 1),
  freeDailyQuota: Number(process.env.FREE_DAILY_QUOTA || 20),
  /** -1 = unlimited for Pro (plan: unlimited) */
  proDailyQuota: Number(process.env.PRO_DAILY_QUOTA || -1),
  adminBootstrapEmail: process.env.ADMIN_EMAIL || 'admin@local',
  adminBootstrapPassword: process.env.ADMIN_PASSWORD || 'admin-change-me',
  requireLeaseForSync: process.env.REQUIRE_LEASE !== '0'
};
