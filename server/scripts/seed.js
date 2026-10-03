const users = require('../src/services/users');
const codes = require('../src/services/codes');
const store = require('../src/db/store');

try {
  users.register('owner@local', 'owner-pass', 'admin');
  console.log('owner@local / owner-pass (admin)');
} catch (e) {
  console.log('owner exists or', e.message);
}
try {
  users.register('tester@local', 'tester-pass', 'user');
  console.log('tester@local / tester-pass (free)');
} catch (e) {
  console.log('tester exists or', e.message);
}
const c = codes.createCode({ plan: 'pro', days: 30, maxUses: 10, note: 'seed pro' });
console.log('Pro code:', c.code);
console.log('Data file:', store.FILE);
