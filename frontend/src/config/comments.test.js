import test from "node:test";
import assert from "node:assert/strict";
import { flattenComments, MAX_VISIBLE_COMMENT_LEVELS } from "./comments.js";

test("comment traversal preserves API sibling order, placeholders and real reply targets", () => {
  const tree = [{ id: "new", username: "author", isDeleted: true, replies: [
    { id: "reply-new", username: "visitor", replies: [], likedUserIds: ["viewer"] },
    { id: "reply-old", replies: [] },
  ] }, { id: "old", replies: [] }];
  const rows = flattenComments(tree, "viewer");
  assert.deepEqual(rows.map((row) => row.id), ["new", "reply-new", "reply-old", "old"]);
  assert.equal(rows[0].isDeleted, true);
  assert.equal(rows[1].parentUsername, "author");
  assert.equal(rows[1].liked, true);
  assert.equal(MAX_VISIBLE_COMMENT_LEVELS, 3);
});

test("deep replies are flattened without a recursive call stack or an artificial depth cap", () => {
  const root = { id: 0, replies: [] };
  let parent = root;
  const depth = 5_000;
  for (let i = 1; i <= depth; i++) {
    const child = { id: i, replies: [] };
    parent.replies.push(child);
    parent = child;
  }
  const rows = flattenComments([root], null);
  assert.equal(rows.length, depth + 1);
  assert.equal(rows.at(-1).id, depth);
  assert.equal(rows.at(-1).depth, depth);
});
