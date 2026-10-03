/**
 * Simple JSON file store (no native deps). Good enough for closed-group Phase 2.
 * Swap for Postgres later without changing route shapes much.
 */
const fs = require('fs');
const path = require('path');

const DATA_DIR = process.env.CHAMET_DATA_DIR || path.join(__dirname, '../../data');
const FILE = path.join(DATA_DIR, 'store.json');

const empty = () => ({
  users: [],
  rounds: [],
  leases: [],
  codes: [],
  audit: []
});

function ensure() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(FILE)) fs.writeFileSync(FILE, JSON.stringify(empty(), null, 2));
}

function read() {
  ensure();
  return JSON.parse(fs.readFileSync(FILE, 'utf8'));
}

function write(data) {
  ensure();
  const tmp = FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, FILE);
}

function update(fn) {
  const data = read();
  const result = fn(data);
  write(data);
  return result;
}

module.exports = { read, write, update, DATA_DIR, FILE };
