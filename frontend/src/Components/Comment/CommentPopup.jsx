import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import style from "./CommentPopup.module.css";
import { api, apiErrorMessage } from "../../config/api";
import { useAuth } from "../../config/auth";
import { useLikes } from "../../config/useLikes";
import { COMMENT_INDENT_PX, MAX_COMMENT_LENGTH, MAX_VISIBLE_COMMENT_LEVELS, flattenComments } from "../../config/comments";
import { toast } from "sonner";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import Timestamp from "../Timestamp";

function checkedContent(value) {
  const content = value.trim();
  if (!content || Array.from(content).length > MAX_COMMENT_LENGTH) {
    toast.warning("Comments must be between 1 and 2,000 characters.");
    return null;
  }
  return content;
}

function CommentItem({ comment, currentUserId, onLike, onReply, onEdit, onDelete }) {
  const [mode, setMode] = useState(null);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const input = useRef(null);
  useEffect(() => { if (mode) input.current?.focus(); }, [mode]);

  const open = (next) => {
    setText(next === "edit" ? comment.content : "");
    setMode(next);
  };
  const submit = async (event) => {
    event.preventDefault();
    if (busy) return;
    const content = mode === "delete" ? null : checkedContent(text);
    if (mode !== "delete" && content === null) return;
    setBusy(true);
    try {
      if (mode === "reply") await onReply(comment.id, content);
      else if (mode === "edit") await onEdit(comment.id, content, comment.version);
      else await onDelete(comment.id, comment.version);
      setMode(null);
      setText("");
    } catch {
      // Keep the draft when the write was not confirmed.
    } finally {
      setBusy(false);
    }
  };
  const author = comment.username ? "@" + comment.username : "Deleted account";
  return (
    <article className={style.commentBlock}
      style={{ marginLeft: Math.min(comment.depth, MAX_VISIBLE_COMMENT_LEVELS - 1) * COMMENT_INDENT_PX }}>
      <div className={style.commentRow}>
        {comment.userId ? (
          <Link to={"/user/" + comment.userId} aria-label={"View profile of " + author}>
            <img src={comment.userAvatarUrl || DEFAULT_AVATAR_URL} alt="" className={style.avatar} />
          </Link>
        ) : <img src={DEFAULT_AVATAR_URL} alt="" className={style.avatar} />}
        <div className={style.commentBody}>
          <div className={style.commentBubble}>
            <div className={style.commentHeader}>
              {comment.userId ? <Link to={"/user/" + comment.userId}><strong>{author}</strong></Link> : <strong>{author}</strong>}
              <Timestamp value={comment.createdAt} />
            </div>
            {comment.depth > 0 && <div className={style.replyContext}>
              Replying to {comment.parentUsername ? "@" + comment.parentUsername : "a comment from a deleted account"}
            </div>}
            <p>{comment.isDeleted ? "Deleted comment" : comment.content}</p>
          </div>
          {!comment.isDeleted && (
            <div className={style.commentActions}>
              <button type="button" className={style.textButton} disabled={!currentUserId || busy}
                onClick={() => open("reply")}>Reply</button>
              {comment.userId === currentUserId && <>
                <button type="button" className={style.textButton} disabled={busy} onClick={() => open("edit")}>Edit</button>
                <button type="button" className={style.textButton} disabled={busy} onClick={() => open("delete")}>Delete</button>
              </>}
              <span className={style.likeCount}>{comment.likesCount || 0} likes</span>
            </div>
          )}
          {mode && !comment.isDeleted && (
            <form className={style.replyForm} onSubmit={submit}>
              {mode === "delete" ? <span>Delete this comment? Replies will be kept.</span> : (
                <input ref={input} type="text" aria-label={mode === "edit" ? "Edit comment" : "Write a reply"}
                  placeholder={mode === "edit" ? "Edit comment..." : "Write a reply..."}
                  value={text} onChange={(event) => setText(event.target.value)} disabled={busy} />
              )}
              <button type="submit" disabled={busy || (mode !== "delete" && !text.trim())}>
                {busy ? "Saving..." : mode === "delete" ? "Confirm deletion" : "Send"}
              </button>
              <button type="button" disabled={busy} onClick={() => setMode(null)}>Cancel</button>
            </form>
          )}
        </div>
        <div className={style.commentAside}>
          <button type="button" className={style.iconButton + (comment.liked ? " " + style.iconButtonActive : "")}
            onClick={() => onLike(comment)} aria-label="Like comment" aria-pressed={comment.liked}
            disabled={!currentUserId || comment.isDeleted}>♥</button>
        </div>
      </div>
    </article>
  );
}

export default function CommentPopup({ postId, onClose, onCommentAdded }) {
  const [comments, setComments] = useState([]);
  const [newComment, setNewComment] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const { user } = useAuth();
  const currentUserId = user?.id || "";
  const base = "/api/v1/blogs/" + postId + "/comments";
  const requestVersion = useRef({ value: 0 });
  const close = useRef(null);
  const panel = useRef(null);
  const closeHandler = useRef(onClose);
  useEffect(() => { closeHandler.current = onClose; }, [onClose]);
  const likes = useLikes(currentUserId, (path, next) => {
    setComments((rows) => rows.map((row) => !row.isDeleted && path === base + "/" + row.id + "/like" ? { ...row, ...next } : row));
  });
  const loadComments = useCallback(async (signal, initial = false) => {
    const counter = requestVersion.current;
    const version = ++counter.value;
    const read = likes.startRead();
    if (initial) setLoading(true);
    setLoadError("");
    try {
      const { data } = await api.get(base, { signal });
      if (!Array.isArray(data)) throw new Error("Invalid comments response");
      if (version === counter.value && !signal?.aborted) {
        setComments(flattenComments(data, currentUserId).map((row) => row.isDeleted ? row
          : likes.merge(base + "/" + row.id + "/like", row, read)));
      }
    } catch (error) {
      if (signal?.aborted || version !== counter.value) return;
      setLoadError(apiErrorMessage(error, "Unable to load comments."));
      if ([401, 403, 404].includes(error.response?.status)) setComments([]);
    } finally {
      if (version === counter.value && !signal?.aborted) setLoading(false);
    }
  }, [base, currentUserId, likes]);

  useEffect(() => {
    const controller = new AbortController();
    const counter = requestVersion.current;
    loadComments(controller.signal, true);
    return () => { controller.abort(); counter.value++; };
  }, [loadComments]);
  useEffect(() => {
    const previousFocus = document.activeElement;
    close.current?.focus();
    const key = (event) => {
      if (event.key === "Escape") closeHandler.current();
      if (event.key !== "Tab") return;
      const controls = [...panel.current.querySelectorAll("button:not(:disabled), input:not(:disabled), a[href]")];
      const first = controls[0];
      const last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault(); last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first?.focus();
      }
    };
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("keydown", key);
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, []);

  const mutate = async (method, path, data) => {
    try {
      const response = await api.request({ method, url: path, data });
      onCommentAdded?.(response.data.commentsCount);
      await loadComments();
      toast.success(response.data.message);
      return response.data;
    } catch (error) {
      if (error.response?.status === 409) await loadComments();
      toast.error(apiErrorMessage(error, "Unable to save your comment. Your text has been kept."));
      throw error;
    }
  };
  const submit = async (event) => {
    event.preventDefault();
    if (submitting || !currentUserId) return;
    const content = checkedContent(newComment);
    if (content === null) return;
    setSubmitting(true);
    try {
      await mutate("post", base, { content });
      setNewComment("");
    } catch {
      // Keep the text so the user can retry deliberately.
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={style.overlay} onClick={onClose}>
      <section ref={panel} className={style.popup} role="dialog" aria-modal="true" aria-labelledby="comments-title"
        onClick={(event) => event.stopPropagation()}>
        <button ref={close} className={style.closeButton} type="button" onClick={onClose} aria-label="Close comments">×</button>
        <h3 id="comments-title">Comments</h3>
        {loadError && <div role="alert"><p>{loadError}</p>
          <button type="button" onClick={() => loadComments()}>Try again</button></div>}
        <div className={style.commentList}>
          {loading ? <p className={style.emptyState}>Loading comments...</p> : comments.length ? comments.map((comment) => (
            <CommentItem key={comment.id} comment={comment} currentUserId={currentUserId}
              onLike={(row) => likes.toggle(base + "/" + row.id + "/like", row.liked, row.likesCount)}
              onReply={(id, content) => mutate("post", base + "/" + id + "/reply", { content })}
              onEdit={(id, content, expectedVersion) => mutate("patch", base + "/" + id, { content, expectedVersion })}
              onDelete={(id, expectedVersion) => mutate("delete", base + "/" + id, { expectedVersion })} />
          )) : !loadError && <p className={style.emptyState}>No comments yet.</p>}
        </div>
        <form className={style.commentInputBox} onSubmit={submit}>
          <input type="text" aria-label="Write a comment" placeholder={currentUserId ? "Write a comment..." : "Sign in to comment"}
            value={newComment} onChange={(event) => setNewComment(event.target.value)}
            disabled={submitting || !currentUserId || Boolean(loadError)} />
          <button type="submit" disabled={submitting || !currentUserId || !newComment.trim() || Boolean(loadError)}>
            {submitting ? "Sending..." : "Send"}
          </button>
        </form>
      </section>
    </div>
  );
}
