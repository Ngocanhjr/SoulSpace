import test from "node:test";
import assert from "node:assert/strict";
import { AxiosError } from "axios";
import { api, resetCsrf } from "./api.js";
import { toggleShare } from "./shares.js";

function adapter(handle) {
  resetCsrf();
  api.defaults.adapter = async (config) => {
    const data = config.url === "/api/v1/auth/csrf" ? { token: "csrf-test" } : await handle(config);
    return { data, status: 200, statusText: "OK", headers: {}, config };
  };
}

function failure(config, status, code) {
  return new AxiosError("Request failed", undefined, config, undefined,
    { status, data: { code }, headers: {}, config });
}

test("sharing sets the viewer state and uses the confirmed server count", async () => {
  const changes = [];
  adapter((config) => {
    assert.equal(config.method, "post");
    assert.equal(config.url, "/api/v1/blogs/blog/share");
    assert.equal(config.headers["X-CSRF-TOKEN"], "csrf-test");
    return { sharesCount: 3 };
  });
  await toggleShare({ id: "blog", sharedByMe: false }, "actor", (next) => changes.push(next));
  assert.deepEqual(changes, [{ sharedByMe: true, sharesCount: 3 }]);
});

test("a second click unshares only the actor's relation and refreshes the canonical count", async () => {
  const methods = [];
  const changes = [];
  adapter((config) => {
    methods.push(config.method);
    if (config.method === "delete") {
      assert.equal(config.url, "/api/v1/users/actor/shared-blogs/blog");
      assert.equal(config.headers["X-CSRF-TOKEN"], "csrf-test");
      return "Shared blog removed successfully";
    }
    return { sharedByMe: false, sharesCount: 2 };
  });
  await toggleShare({ id: "blog", sharedByMe: true }, "actor", (next) => changes.push(next));
  assert.deepEqual(methods, ["delete", "get"]);
  assert.deepEqual(changes, [{ sharedByMe: false }, { sharedByMe: false, sharesCount: 2 }]);
});

test("failed refresh after committed unshare keeps the successful state and warns", async () => {
  const changes = [];
  adapter((config) => {
    if (config.method === "delete") return "OK";
    throw new AxiosError("offline");
  });
  const result = await toggleShare({ id: "blog", sharedByMe: true }, "actor", (next) => changes.push(next));
  assert.equal(result.warning, true);
  assert.deepEqual(changes, [{ sharedByMe: false }]);
});

test("a now-private or deleted source is removed after successful unshare", async () => {
  adapter((config) => {
    if (config.method === "delete") return "OK";
    throw failure(config, 404, "BLOG_NOT_FOUND");
  });
  const result = await toggleShare({ id: "blog", sharedByMe: true }, "actor", () => {});
  assert.equal(result.unavailable, true);
});

test("a stale unshared button reads existing state after ALREADY_SHARED without toggling it off", async () => {
  const methods = [];
  const changes = [];
  adapter((config) => {
    methods.push(config.method);
    if (config.method === "post") throw failure(config, 409, "ALREADY_SHARED");
    return { sharedByMe: true, sharesCount: 4 };
  });
  await toggleShare({ id: "blog", sharedByMe: false }, "actor", (next) => changes.push(next));
  assert.deepEqual(methods, ["post", "get"]);
  assert.deepEqual(changes, [{ sharedByMe: true, sharesCount: 4 }]);
});

test("an unconfirmed delete failure preserves the button state", async () => {
  const changes = [];
  adapter(() => { throw new AxiosError("offline"); });
  await assert.rejects(toggleShare({ id: "blog", sharedByMe: true }, "actor", (next) => changes.push(next)));
  assert.deepEqual(changes, []);
});
