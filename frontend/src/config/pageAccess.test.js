import test from "node:test";
import assert from "node:assert/strict";
import { getPageAccess } from "./pageAccess.js";

const anonymous = { user: null, ready: true, error: null };

test("visitors must choose guest mode before any page content is mounted", () => {
  for (const path of ["/", "/home", "/search", "/post", "/user", "/user/author"]) {
    assert.equal(getPageAccess(anonymous, false, path), "prompt");
  }
});

test("guests see the home feed but every other tab requires sign in", () => {
  for (const path of ["/", "/home"]) {
    assert.equal(getPageAccess(anonymous, true, path), "content");
  }
  for (const path of ["/search", "/post", "/user", "/user/author", "/search/post/blog"]) {
    assert.equal(getPageAccess(anonymous, true, path), "prompt");
  }
});

test("session checks and failures never expose page content", () => {
  assert.equal(getPageAccess({ ...anonymous, ready: false }, true, "/"), "pending");
  assert.equal(getPageAccess({ ...anonymous, error: "Offline" }, true, "/search"), "error");
});

test("signed in users retain access to all tabs", () => {
  for (const path of ["/", "/search", "/post", "/user", "/user/author"]) {
    assert.equal(getPageAccess({ ...anonymous, user: { id: "member" } }, false, path), "content");
  }
});
