import assert from "node:assert/strict";
import test from "node:test";
import process from "node:process";
import { formatDateTime, formatRelativeTime } from "../src/lib/time.js";

const now = Date.parse("2026-10-27T05:00:00Z");

test("relative times cover recent, past and future timestamps", () => {
  assert.equal(formatRelativeTime(now - 59000, now), "Just now");
  assert.equal(formatRelativeTime(now - 60000, now), "1 minute ago");
  assert.equal(formatRelativeTime(now - 5 * 60000, now), "5 minutes ago");
  assert.equal(formatRelativeTime(now - 3600000, now), "1 hour ago");
  assert.equal(formatRelativeTime(now - 86400000, now), "1 day ago");
  assert.equal(formatRelativeTime(now - 30 * 86400000, now), "1 month ago");
  assert.equal(formatRelativeTime(now - 365 * 86400000, now), "1 year ago");
  assert.equal(formatRelativeTime(new Date(now - 5 * 60000).toISOString(), now), "5 minutes ago");
  assert.match(formatRelativeTime(now + 5 * 60000, now), /^in 5 minutes/);
});

test("invalid or missing dates do not become 1970 or Invalid Date", () => {
  for (const value of [null, undefined, "", "invalid-date"]) {
    assert.equal(formatDateTime(value), "");
    assert.equal(formatRelativeTime(value, now), "");
  }
});

test("full dates use Vietnam time across midnight regardless of device timezone", () => {
  const originalZone = process.env.TZ;
  try {
    for (const zone of ["UTC", "America/New_York"]) {
      process.env.TZ = zone;
      const result = formatDateTime("2026-10-26T17:30:00Z");
      assert.match(result, /00:30:00/);
      assert.match(result, /27\/10\/2026/);
    }
  } finally {
    if (originalZone === undefined) delete process.env.TZ;
    else process.env.TZ = originalZone;
  }
});
