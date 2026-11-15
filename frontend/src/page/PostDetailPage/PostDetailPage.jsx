import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { useAuth } from "../../config/auth";
import { useLikes } from "../../config/useLikes";
import { toast } from "sonner";
import { ArrowLeft } from "lucide-react";
import CommentPopup from "../../Components/Comment/CommentPopup.jsx";
import style from "./PostDetailPage.module.css";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import Timestamp from "../../Components/Timestamp";

export default function PostDetailPage() {
  const { postId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [post, setPost] = useState(null);
  const [loading, setLoading] = useState(true);
  const [isCommentPopupOpen, setIsCommentPopupOpen] = useState(false);

  const { user } = useAuth();
  const currentUserId = user?.id || "";
  const [sharing, setSharing] = useState(false);
  const likes = useLikes(currentUserId, (path, next) => {
    setPost((current) => current && path === "/api/v1/blogs/" + current.id + "/like" ? { ...current, ...next } : current);
  });

  const isSearchMode = useMemo(
    () => location.pathname.startsWith("/search/"),
    [location.pathname]
  );

  useEffect(() => {
    const controller = new AbortController();
    const fetchPost = async () => {
      const read = likes.startRead();
      try {
        setLoading(true);
        const response = await api.get(`/api/v1/blogs/${postId}`, { signal: controller.signal });
        if (controller.signal.aborted) return;

        let nextPost = response.data || null;

        if (nextPost) {
          nextPost.liked =
            Array.isArray(nextPost.likedUserIds) && currentUserId
              ? nextPost.likedUserIds.includes(currentUserId)
              : false;
          nextPost.likesCount = nextPost.likesCount || 0;
          nextPost.commentsCount = nextPost.commentsCount || 0;
          nextPost.sharesCount = nextPost.sharesCount || 0;
          nextPost = likes.merge("/api/v1/blogs/" + postId + "/like", nextPost, read);
        }

        setPost(nextPost);
      } catch (error) {
        if (controller.signal.aborted) return;
        console.error("Cannot get post detail:", error);
        toast.error("Không thể tải chi tiết bài viết");
        setPost(null);
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    };

    if (postId) {
      fetchPost();
    }
    return () => controller.abort();
  }, [postId, currentUserId, likes]);

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
    if (type === "COMMENT") {
      setIsCommentPopupOpen(true);
      return;
    }
    if (!currentUserId) {
      toast.error("Vui lòng đăng nhập để tương tác.");
      return;
    }
    if (type === "LIKE") {
      likes.toggle("/api/v1/blogs/" + post.id + "/like", post.liked, post.likesCount);
      return;
    }
    if (sharing || !post.published) return;
    setSharing(true);
    try {
      const { data } = await api.post("/api/v1/blogs/" + post.id + "/share", {});
      setPost((current) => current?.id === post.id ? { ...current, sharesCount: data.sharesCount } : current);
      toast.success(data.message);
    } catch (error) {
      toast.error(apiErrorMessage(error, "Không thể chia sẻ bài viết."));
    } finally {
      setSharing(false);
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
              src={post.userAvatarUrl || DEFAULT_AVATAR_URL}
              alt="User Avatar"
              className={style.avatar}
            />
            <div className={style.userInfo}>
              <strong>{post.username ? `@${post.username}` : "Unknown user"}</strong>
                <Timestamp value={post.createdAt} relative={false} />
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
              aria-pressed={post.liked}
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
              disabled={sharing || !post.published}
              title={post.published ? "Chia sẻ bài viết" : "Chỉ chia sẻ được bài công khai"}
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
