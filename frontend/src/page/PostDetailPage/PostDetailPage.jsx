import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { toast } from "sonner";
import { ArrowLeft } from "lucide-react";
import CommentPopup from "../../Components/Comment/CommentPopup.jsx";
import style from "./PostDetailPage.module.css";
import { assetUrl } from "../../config/assets";

export default function PostDetailPage() {
  const { postId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [post, setPost] = useState(null);
  const [loading, setLoading] = useState(true);
  const [isCommentPopupOpen, setIsCommentPopupOpen] = useState(false);

  const currentUserId = String(localStorage.getItem("userId") || "").replaceAll(
    '"',
    ""
  );

  const isSearchMode = useMemo(
    () => location.pathname.startsWith("/search/"),
    [location.pathname]
  );

  useEffect(() => {
    const fetchPost = async () => {
      try {
        setLoading(true);
        const response = await axios.get(
          buildApiUrl(`/api/v1/blogs/${postId}`)
        );

        const nextPost = response.data || null;

        if (nextPost) {
          nextPost.liked =
            Array.isArray(nextPost.likedUserIds) && currentUserId
              ? nextPost.likedUserIds.includes(currentUserId)
              : false;
          nextPost.likesCount = nextPost.likesCount || 0;
          nextPost.commentsCount = nextPost.commentsCount || 0;
          nextPost.sharesCount = nextPost.sharesCount || 0;
        }

        setPost(nextPost);
      } catch (error) {
        console.error("Cannot get post detail:", error);
        toast.error("Không thể tải chi tiết bài viết");
        setPost(null);
      } finally {
        setLoading(false);
      }
    };

    if (postId) {
      fetchPost();
    }
  }, [postId, currentUserId]);

  const handleBack = () => {
    if (isSearchMode) {
      navigate("/search");
      return;
    }

    navigate(-1);
  };

  const handleCommentAdded = (nextCommentsCount) => {
    setPost((prev) =>
      prev
        ? {
            ...prev,
            commentsCount:
              typeof nextCommentsCount === "number"
                ? nextCommentsCount
                : (prev.commentsCount || 0) + 1,
          }
        : prev
    );
  };

  const handleAction = async (type) => {
    if (!post) return;

    if (type === "LIKE") {
      if (!currentUserId) {
        toast.error("Please sign in to like posts.");
        return;
      }

      try {
        const response = await axios.post(
          buildApiUrl(`/api/v1/blogs/${post.id}/like`),
          {
            userId: currentUserId,
          }
        );

        setPost((prev) =>
          prev
            ? {
                ...prev,
                liked: !!response.data?.liked,
                likesCount:
                  typeof response.data?.likesCount === "number"
                    ? response.data.likesCount
                    : prev.likesCount,
              }
            : prev
        );
      } catch (error) {
        console.error("Error liking post:", error);
        toast.error(
          error?.response?.data?.message ||
            error?.response?.data ||
            "Failed to like post."
        );
      }
    }

    if (type === "COMMENT") {
      setIsCommentPopupOpen(true);
    }

    if (type === "SHARE") {
      if (!currentUserId) {
        toast.error("Please sign in to share posts.");
        return;
      }

      try {
        const response = await axios.post(
          buildApiUrl(`/api/v1/blogs/${post.id}/share`),
          { userId: currentUserId }
        );

        setPost((prev) =>
          prev
            ? {
                ...prev,
                sharesCount:
                  typeof response?.data?.sharesCount === "number"
                    ? response.data.sharesCount
                    : (prev.sharesCount || 0) + 1,
              }
            : prev
        );

        toast.success(response?.data?.message || "Chia sẻ bài viết thành công");
      } catch (error) {
        console.error("Error sharing post:", error);
        toast.error(
          error?.response?.data?.message || "You have already shared it."
        );
      }
    }
  };

  if (loading) {
    return <div className={style.state}>Đang tải bài viết...</div>;
  }

  if (!post) {
    return <div className={style.state}>Không tìm thấy bài viết.</div>;
  }

  return (
    <>
      <div className={style.wrapper}>
        <div className={style.topBar}>
          <button
            type="button"
            className={style.backButton}
            onClick={handleBack}
          >
            <ArrowLeft size={18} />
          </button>
          <h1 className={style.title}>Bài đăng</h1>
        </div>

        <div className={style.card}>
          <div className={style.header}>
            <img
              src={post.userAvatarUrl || assetUrl("default-avatar.png")}
              alt="User Avatar"
              className={style.avatar}
            />
            <div className={style.userInfo}>
              <strong>{post.username ? `@${post.username}` : "Unknown user"}</strong>
              <span>
                {post.createdAt
                  ? new Date(post.createdAt).toLocaleString("vi-VN", {
                      hour12: false,
                    })
                  : ""}
              </span>
            </div>
          </div>

          <div className={style.content}>{post.content}</div>

          {Array.isArray(post.imageContentUrls) &&
            post.imageContentUrls.map((url, index) => (
              <img
                key={index}
                src={url}
                className={style.image}
                alt={`Post image ${index}`}
              />
            ))}

          <div className={style.statusBar}>
            <button
              type="button"
              className={`${style.btn} ${style.btnLike} ${
                post.liked ? style.btnLikeActive : ""
              }`}
              aria-label="Like"
              onClick={() => handleAction("LIKE")}
            >
              <span className={style.icon} />
            </button>
            <span>{post.likesCount}</span>

            <button
              type="button"
              className={`${style.btn} ${style.btnComment}`}
              aria-label="Comment"
              onClick={() => handleAction("COMMENT")}
            >
              <span className={style.icon} />
            </button>
            <span>{post.commentsCount}</span>

            <button
              type="button"
              className={`${style.btn} ${style.btnShare}`}
              aria-label="Share"
              onClick={() => handleAction("SHARE")}
            >
              <span className={style.icon} />
            </button>
            <span>{post.sharesCount}</span>
          </div>
        </div>
      </div>

      {isCommentPopupOpen && (
        <CommentPopup
          postId={post.id}
          onClose={() => setIsCommentPopupOpen(false)}
          onCommentAdded={handleCommentAdded}
        />
      )}
    </>
  );
}