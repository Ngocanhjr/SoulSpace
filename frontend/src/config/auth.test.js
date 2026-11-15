import test from "node:test";
import assert from "node:assert/strict";
import { AxiosError } from "axios";
import { DEFAULT_AVATAR_URL } from "./assets.js";

test("auth sends CSRF and cookies, refreshes tokens, and only clears auth on AUTH_REQUIRED", async () => {
  const store = new Map();
  globalThis.window = new EventTarget();
  globalThis.localStorage = {
    getItem: (key) => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, value),
    removeItem: (key) => store.delete(key),
  };
  const { api } = await import("./api.js");
  const { signIn, signOut, refreshSession } = await import("./auth.js");
  const user = { id: "test-user", username: "user01", avatarUrl: DEFAULT_AVATAR_URL };
  let token = "before-login";
  let loggedIn = false;
  let failureCode = null;
  const requests = [];
  api.defaults.adapter = async (config) => {
    requests.push(config.url);
    assert.equal(config.withCredentials, true);
    const response = { config, headers: {}, status: 200, statusText: "OK" };
    if (config.method === "post") {
      assert.equal(config.headers.get("X-CSRF-TOKEN"), token);
    }
    if (failureCode) {
      response.status = failureCode === "SERVICE_UNAVAILABLE" ? 503 : 401;
      response.data = { code: failureCode };
      throw new AxiosError("test failure", "ERR_BAD_RESPONSE", config, null, response);
    }
    if (config.url.endsWith("/csrf")) response.data = { token };
    if (config.url.endsWith("/log-in")) {
      loggedIn = true;
      token = "after-login";
      response.data = { authentication: true, user };
    }
    if (config.url.endsWith("/me")) {
      response.data = { authentication: loggedIn, user: loggedIn ? user : null };
    }
    if (config.url.endsWith("/log-out")) {
      loggedIn = false;
      token = "after-logout";
      response.data = { message: "Logged out successfully" };
    }
    return response;
  };

  // A stale compatibility flag must not authenticate a user on reload.
  store.set("isLoggedIn", "true");
  await refreshSession();
  assert.equal(store.get("isLoggedIn"), undefined);

  requests.length = 0;
  await signIn("user01", "test-password");
  assert.deepEqual(requests, ["/api/v1/auth/csrf", "/api/v1/auth/log-in",
    "/api/v1/auth/csrf", "/api/v1/auth/me"]);
  assert.equal(store.get("isLoggedIn"), "true");
  assert.equal(store.get("password"), undefined);

  failureCode = "INVALID_CREDENTIALS";
  await assert.rejects(api.post("/api/v1/auth/log-in", {}));
  assert.equal(store.get("isLoggedIn"), "true");

  failureCode = "SERVICE_UNAVAILABLE";
  await assert.rejects(signOut());
  assert.equal(store.get("isLoggedIn"), "true");

  failureCode = null;
  await signOut();
  assert.equal(store.get("isLoggedIn"), undefined);

  await signIn("user01", "test-password");
  failureCode = "AUTH_REQUIRED";
  await assert.rejects(api.get("/api/v1/auth/me"));
  assert.equal(store.get("isLoggedIn"), undefined);
});
