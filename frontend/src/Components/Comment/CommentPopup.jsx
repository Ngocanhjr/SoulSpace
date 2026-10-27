import { useEffect, useMemo, useState } from "react";
import style from "./CommentPopup.module.css";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { toast } from "sonner";
import { assetUrl } from "../../config/assets";
import Timestamp from "../Timestamp";

function HeartButton({ liked, onClick }) {
  return (
    <button
      type="button"
      className={`${style.iconButton} ${liked ? style.iconButtonActive : ""}`}
      onClick={onClick}
      aria-label="Like comment"
    >
      ♥
    </button>
  );
}

function CommentItem({
  comment,
  postId,
  currentUserId,
  onToggleLike,
  onReplySubmit,
  level = 0,
}) {
  const [isReplying, setIsReplying] = useState(false);
  const [replyText, setReplyText] = useState("");
  const [isSubmittingReply, setIsSubmittingReply] = useState(false);

  const liked = Array.isArray(comment.likedUserIds)
    ? comment.likedUserIds.includes(currentUserId)
    : false;

  const handleSubmitReply = async (e) => {
    e.preventDefault();

    const content = replyText.trim();
    if (!content || !currentUserId || isSubmittingReply) return;

    try {
      setIsSubmittingReply(true);
      await onReplySubmit(comment.id, content);
      setReplyText("");
      setIsReplying(false);
    } finally {
      setIsSubmittingReply(false);
    }
  };

  return (
    <div className={`${style.commentBlock} ${level > 0 ? style.replyBlock : ""}`}>
      <div className={style.commentRow}>
        <img
          src={comment.userAvatarUrl || assetUrl("default-avatar.png")}
          alt="Comment avatar"
          className={style.avatar}
        />

        <div className={style.commentBody}>
          <div className={style.commentBubble}>
            <div className={style.commentHeader}>
              <strong>
                {comment.username ? `@${comment.username}` : "Unknown user"}
              </strong>
              <Timestamp value={comment.createdAt} />
            </div>
            <p>{comment.content}</p>
          </div>

          <div className={style.commentActions}>
            <button
              type="button"
              className={style.textButton}
              onClick={() => setIsReplying((prev) => !prev)}
            >
              {isReplying ? "Huỷ" : "Phản hồi"}
            </button>
            <span className={style.likeCount}>{comment.likesCount || 0} tim</span>
          </div>

          {isReplying && (
            <form className={style.replyForm} onSubmit={handleSubmitReply}>
              <input
                type="text"
                placeholder="Viết phản hồi..."
                value={replyText}
                onChange={(e) => setReplyText(e.target.value)}
                disabled={isSubmittingReply}
              />
              <button
                type="submit"
                disabled={isSubmittingReply || !replyText.trim()}
              >
                {isSubmittingReply ? "..." : "Trả lời"}
              </button>
            </form>
          )}

          {Array.isArray(comment.replies) && comment.replies.length > 0 && (
            <div className={style.replyList}>
              {comment.replies.map((reply) => (
                <CommentItem
                  key={reply.id}
                  comment={reply}
                  postId={postId}
                  currentUserId={currentUserId}
                  onToggleLike={onToggleLike}
                  onReplySubmit={onReplySubmit}
                  level={level + 1}
                />
              ))}
            </div>
          )}
        </div>

        <div className={style.commentAside}>
          <HeartButton liked={liked} onClick={() => onToggleLike(comment.id)} />
        </div>
      </div>
    </div>
  );
}

export default function CommentPopup({ postId, onClose, onCommentAdded }) {
  const [comments, setComments] = useState([]);
  const [newComment, setNewComment] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const currentUserId = useMemo(
    () => String(localStorage.getItem("userId") || "").replaceAll('"', ""),
    []
  );

  useEffect(() => {
    const fetchComments = async () => {
      setIsLoading(true);
      try {
        const rs = await axios.get(
          buildApiUrl(`/api/v1/blogs/${postId}/comments`)
        );
        setComments(Array.isArray(rs.data) ? rs.data : []);
      } catch (error) {
        console.error("Không thể tải bình luận:", error);
        toast.error("Không thể tải bình luận");
      } finally {
        setIsLoading(false);
      }
    };

    if (postId) {
      fetchComments();
    }
  }, [postId]);

  const updateCommentTree = (items, targetId, updater) =>
    items.map((item) => {
      if (item.id === targetId) {
        return updater(item);
      }

      if (Array.isArray(item.replies) && item.replies.length > 0) {
        return {
          ...item,
          replies: updateCommentTree(item.replies, targetId, updater),
        };
      }

      return item;
    });

  const appendReplyToTree = (items, targetId, reply) =>
    items.map((item) => {
      if (item.id === targetId) {
        return {
          ...item,
          replies: [...(Array.isArray(item.replies) ? item.replies : []), reply],
        };
      }

      if (Array.isArray(item.replies) && item.replies.length > 0) {
        return {
          ...item,
          replies: appendReplyToTree(item.replies, targetId, reply),
        };
      }

      return item;
    });

  const handleCommentSubmit = async (e) => {
    e.preventDefault();

    const content = newComment.trim();
    if (!content || isSubmitting) return;

    if (!currentUserId) {
      toast.error("Please sign in to comment.");
      return;
    }

    try {
      setIsSubmitting(true);

      const rs = await axios.post(
        buildApiUrl(`/api/v1/blogs/${postId}/comments`),
        {
          userId: currentUserId,
          content,
        }
      );

      const createdComment = rs.data?.comment;
      const nextCommentsCount = rs.data?.commentsCount;

      if (createdComment) {
        setComments((prevComments) => [...prevComments, createdComment]);
      }

      if (typeof onCommentAdded === "function") {
        onCommentAdded(nextCommentsCount);
      }

      setNewComment("");
      toast.success(rs.data?.message || "Gửi bình luận thành công");
    } catch (error) {
      console.error("Không thể gửi bình luận:", error);
      toast.error(
        error?.response?.data?.message ||
          error?.response?.data ||
          "Gửi bình luận thất bại!"
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleReplySubmit = async (commentId, content) => {
    if (!currentUserId) {
      toast.error("Please sign in to reply.");
      return;
    }

    try {
      const rs = await axios.post(
        buildApiUrl(`/api/v1/blogs/${postId}/comments/${commentId}/reply`),
        {
          userId: currentUserId,
          content,
        }
      );

      const createdReply = rs.data?.reply;
      const nextCommentsCount = rs.data?.commentsCount;

      if (createdReply) {
        setComments((prevComments) =>
          appendReplyToTree(prevComments, commentId, createdReply)
        );
      }

      if (typeof onCommentAdded === "function") {
        onCommentAdded(nextCommentsCount);
      }

      toast.success(rs.data?.message || "Phản hồi thành công");
    } catch (error) {
      console.error("Không thể phản hồi bình luận:", error);
      toast.error(
        error?.response?.data?.message ||
          error?.response?.data ||
          "Phản hồi thất bại!"
      );
      throw error;
    }
  };

  const handleToggleCommentLike = async (commentId) => {
    if (!currentUserId) {
      toast.error("Please sign in to like comments.");
      return;
    }

    try {
      const rs = await axios.post(
        buildApiUrl(`/api/v1/blogs/${postId}/comments/${commentId}/like`),
        {
          userId: currentUserId,
        }
      );

      setComments((prevComments) =>
        updateCommentTree(prevComments, commentId, (comment) => {
          const previousLikedUserIds = Array.isArray(comment.likedUserIds)
            ? comment.likedUserIds
            : [];

          return {
            ...comment,
            likesCount:
              typeof rs.data?.likesCount === "number"
                ? rs.data.likesCount
                : comment.likesCount || 0,
            likedUserIds: rs.data?.liked
              ? [...new Set([...previousLikedUserIds, currentUserId])]
              : previousLikedUserIds.filter((id) => id !== currentUserId),
          };
        })
      );
    } catch (error) {
      console.error("Không thể thích bình luận:", error);
      toast.error(
        error?.response?.data?.message ||
          error?.response?.data ||
          "Không thể thả tim bình luận"
      );
    }
  };

  return (
    <div className={style.overlay} onClick={onClose}>
      <div className={style.popup} onClick={(e) => e.stopPropagation()}>
        <button className={style.closeButton} onClick={onClose}>
          &times;
        </button>
        <h3>Bình luận</h3>

        <div className={style.commentList}>
          {isLoading ? (
            <p className={style.emptyState}>Đang tải bình luận...</p>
          ) : comments.length > 0 ? (
            comments.map((comment) => (
              <CommentItem
                key={comment.id}
                comment={comment}
                postId={postId}
                currentUserId={currentUserId}
                onToggleLike={handleToggleCommentLike}
                onReplySubmit={handleReplySubmit}
              />
            ))
          ) : (
            <p className={style.emptyState}>
              Chưa có bình luận nào. Hãy là người đầu tiên!
            </p>
          )}
        </div>

        <form className={style.commentInputBox} onSubmit={handleCommentSubmit}>
          <input
            type="text"
            placeholder="Viết bình luận..."
            value={newComment}
            onChange={(e) => setNewComment(e.target.value)}
            disabled={isSubmitting}
          />
          <button type="submit" disabled={isSubmitting || !newComment.trim()}>
            {isSubmitting ? "..." : "Gửi"}
          </button>
        </form>
      </div>
    </div>
  );
}
