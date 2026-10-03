/**
 * Session 21 — closed beta waitlist + launch flags.
 */
const store = require('../db/store');
const { uuid } = require('../util');

function joinWaitlist(email, note) {
  email = String(email || '').trim().toLowerCase();
  if (!email || !email.includes('@')) {
    const err = new Error('invalid_email');
    err.status = 400;
    throw err;
  }
  return store.update((data) => {
    if (!data.waitlist) data.waitlist = [];
    if (data.waitlist.some((w) => w.email === email)) {
      return { email, status: 'already' };
    }
    const row = {
      id: uuid(),
      email,
      note: note || '',
      at: new Date().toISOString(),
      invited: false
    };
    data.waitlist.push(row);
    return { email, status: 'joined' };
  });
}

function listWaitlist() {
  return store.read().waitlist || [];
}

function launchConfig() {
  const data = store.read();
  return data.launch || {
    publicLaunch: false,
    closedBeta: true,
    message: 'Closed beta — request access on the landing page.'
  };
}

function setLaunchConfig(cfg) {
  return store.update((data) => {
    data.launch = {
      publicLaunch: !!cfg.publicLaunch,
      closedBeta: cfg.closedBeta !== false,
      message: cfg.message || data.launch?.message || ''
    };
    return data.launch;
  });
}

module.exports = { joinWaitlist, listWaitlist, launchConfig, setLaunchConfig };
