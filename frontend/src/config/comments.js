export const MAX_VISIBLE_COMMENT_LEVELS = 3;
export const COMMENT_INDENT_PX = 24;
export const MAX_COMMENT_LENGTH = 2_000;

export function flattenComments(tree, viewerId) {
  const rows = [];
  const pending = tree.slice().reverse().map((comment) => ({ comment, depth: 0, parentUsername: null }));
  while (pending.length) {
    const { comment, depth, parentUsername } = pending.pop();
    const { replies, ...fields } = comment;
    rows.push({ ...fields, depth, parentUsername, liked: (comment.likedUserIds || []).includes(viewerId) });
    for (let i = (replies || []).length - 1; i >= 0; i--) {
      pending.push({ comment: replies[i], depth: depth + 1, parentUsername: comment.username });
    }
  }
  return rows;
}
