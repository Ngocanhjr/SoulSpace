import test from "node:test";
import assert from "node:assert/strict";

test("Google redirects store only the UI action and refresh the session CSRF after callback", async () => {
  const store = new Map();
  globalThis.window = new EventTarget();
  const targets = [];
  window.location = { assign: (url) => targets.push(url) };
  globalThis.sessionStorage = { getItem: (key) => store.get(key) ?? null, setItem: (key, value) => store.set(key, value), removeItem: (key) => store.delete(key) };
  globalThis.localStorage = { setItem() {}, removeItem() {} };
  const { api } = await import("./api.js");
  const { startGoogle, takeGoogleAction, refreshGoogleSession } = await import("./google.js");
  const requests = [];
  let destination = "https://accounts.google.com/o/oauth2/v2/auth?state=example";
  let csrf = "before";
  api.defaults.adapter = async (config) => {
    requests.push(config);
    if (config.method === "post") assert.equal(config.headers["X-CSRF-TOKEN"], csrf);
    const data = config.url.endsWith("/csrf") ? { token: csrf } : config.url.endsWith("/me") ? { user: { id: "user" } } : { authorizationUrl: destination };
    return { data, status: 200, statusText: "OK", headers: {}, config };
  };
  await startGoogle("REAUTH", { action: "DELETE_ACCOUNT" });
  assert.equal(targets.length, 1);
  assert.deepEqual([...store.values()], ["DELETE_ACCOUNT"]);
  assert.equal(takeGoogleAction(), "DELETE_ACCOUNT");
  assert.equal(takeGoogleAction(), null);
  destination = "https://untrusted.example/";
  await assert.rejects(startGoogle(), /Invalid Google authorization URL/);
  assert.equal(targets.length, 1);
  csrf = "after";
  assert.deepEqual(await refreshGoogleSession(), { id: "user" });
  assert.deepEqual(requests.slice(-2).map((request) => request.url), ["/api/v1/auth/csrf", "/api/v1/auth/me"]);
});
