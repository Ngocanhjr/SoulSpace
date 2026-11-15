import test from "node:test";
import assert from "node:assert/strict";
import { createLikeQueue } from "./useLikes.js";

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

test("reads preserve pending intent and late responses cannot overwrite newer confirmed likes", async () => {
  const request = deferred();
  const queue = createLikeQueue(() => request.promise, () => {}, assert.fail);
  const done = queue.toggle("blog", false, 0);
  const oldRead = queue.startRead();
  assert.deepEqual(queue.merge("blog", { liked: false, likesCount: 0 }, oldRead), { liked: true, likesCount: 1 });
  request.resolve({ liked: true, likesCount: 2 });
  await done;
  assert.deepEqual(queue.merge("blog", { liked: false, likesCount: 0 }, oldRead), { liked: true, likesCount: 2 });
  const freshRead = queue.startRead();
  assert.deepEqual(queue.merge("blog", { liked: true, likesCount: 5 }, freshRead), { liked: true, likesCount: 5 });
});

test("likes update immediately and coalesce rapid clicks into the final intent", async () => {
  const first = deferred();
  const second = deferred();
  const writes = [];
  const updates = [];
  const queue = createLikeQueue((path, liked) => {
    writes.push({ path, liked });
    return writes.length === 1 ? first.promise : second.promise;
  }, (path, next) => updates.push(next), assert.fail);
  const done = queue.toggle("blog", false, 0);
  queue.toggle("blog", true, 1);
  assert.deepEqual(updates.at(-1), { liked: false, likesCount: 0 });
  assert.equal(writes.length, 1);
  first.resolve({ liked: true, likesCount: 5 });
  await Promise.resolve();
  await Promise.resolve();
  assert.deepEqual(writes, [{ path: "blog", liked: true }, { path: "blog", liked: false }]);
  assert.deepEqual(updates.at(-1), { liked: false, likesCount: 4 });
  second.resolve({ liked: false, likesCount: 4 });
  await done;
  assert.deepEqual(updates.at(-1), { liked: false, likesCount: 4 });
});

test("a failed write rolls back and a new click can retry", async () => {
  const updates = [];
  const failures = [];
  const queue = createLikeQueue(async () => { throw new Error("offline"); },
    (path, next) => updates.push(next), (failure) => failures.push(failure));
  await queue.toggle("comment", false, 2);
  assert.deepEqual(updates, [{ liked: true, likesCount: 3 }, { liked: false, likesCount: 2 }]);
  await queue.toggle("comment", false, 2);
  assert.equal(failures.length, 2);
});

test("a failed older request preserves newer intent and retries only that intent", async () => {
  const first = deferred();
  const writes = [];
  const updates = [];
  const queue = createLikeQueue(async (path, liked) => {
    writes.push(liked);
    if (writes.length === 1) return first.promise;
    return { liked, likesCount: 1 };
  }, (path, next) => updates.push(next), () => {});
  const done = queue.toggle("blog", false, 0);
  queue.toggle("blog", true, 1);
  queue.toggle("blog", false, 0);
  first.reject(new Error("uncertain response"));
  await done;
  assert.deepEqual(writes, [true, true]);
  assert.deepEqual(updates.at(-1), { liked: true, likesCount: 1 });
});
