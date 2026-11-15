import { useState, useEffect, useCallback } from "react";
import style from "./ListPost.module.css";
import { toast } from "sonner";
import CommentPopup from "../Comment/CommentPopup.jsx";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import Timestamp from "../Timestamp";
import { Link } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { useAuth } from "../../config/auth";
import { useLikes } from "../../config/useLikes";
const CONTENT_PREVIEW_LENGTH = 220;

export default function ListPost() {
  const [data, setData] = useState([]);
  const [isCommentPopupOpen, setIsCommentPopupOpen] = useState(false);
  const [selectedPostId, setSelectedPostId] = useState(null);
  const [expandedPosts, setExpandedPosts] = useState({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const { user } = useAuth();
  const currentUserId = user?.id || "";
  const [sharing, setSharing] = useState({});
  const likes = useLikes(currentUserId, (path, next) => {
    setData((rows) => rows.map((post) => path === "/api/v1/blogs/" + post.id + "/like" ? { ...post, ...next } : post));
  });

  const fetchPosts = useCallback(async (signal) => {
    const read = likes.startRead();
    setLoading(true);
    setLoadError("");
    try {
      const rs = await api.get("/api/v1/soulspaces", { signal });
      if (signal?.aborted) return;

      if (!Array.isArray(rs.data)) {
        console.error("API không trả về mảng hợp lệ:", rs.data);
        toast.error("Invalid API response format");
        return;
      }

      const processedData = rs.data.map((post) => likes.merge("/api/v1/blogs/" + post.id + "/like", {
        ...post,
        liked:
          Array.isArray(post.likedUserIds) && currentUserId
            ? post.likedUserIds.includes(currentUserId)
            : false,
        likesCount: post.likesCount || 0,
        commentsCount: post.commentsCount || 0,
        sharesCount: post.sharesCount || 0,
      }, read));

      setData(processedData);
    } catch (error) {
      if (signal?.aborted) return;
      setLoadError(apiErrorMessage(error, "Không thể tải bài viết."));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, [currentUserId, likes]);

  useEffect(() => {
    const controller = new AbortController();
    fetchPosts(controller.signal);
    return () => controller.abort();
  }, [fetchPosts]);

  const toggleExpand = (postId) => {
    setExpandedPosts((prev) => ({
      ...prev,
      [postId]: !prev[postId],
    }));
  };

  const handleCommentAdded = (postId, nextCommentsCount) => {
    setData((prevData) =>
      prevData.map((post) =>
        post.id === postId
          ? {
              ...post,
              commentsCount:
                typeof nextCommentsCount === "number"
                  ? nextCommentsCount
                  : (post.commentsCount || 0) + 1,
            }
          : post
      )
    );
  };

  const handleAction = async (postId, type) => {
    if (type === "COMMENT") {
      setSelectedPostId(postId);
      setIsCommentPopupOpen(true);
      return;
    }
    if (!currentUserId) {
      toast.error("Vui lòng đăng nhập để tương tác.");
      return;
    }
    if (type === "LIKE") {
      const post = data.find((item) => item.id === postId);
      if (post) likes.toggle("/api/v1/blogs/" + postId + "/like", post.liked, post.likesCount);
      return;
    }
    if (sharing[postId]) return;
    setSharing((pending) => ({ ...pending, [postId]: true }));
    try {
      const { data: result } = await api.post("/api/v1/blogs/" + postId + "/share", {});
      setData((rows) => rows.map((post) => post.id === postId ? { ...post, sharesCount: result.sharesCount } : post));
      toast.success(result.message);
    } catch (error) {
      toast.error(apiErrorMessage(error, "Không thể chia sẻ bài viết."));
    } finally {
      setSharing((pending) => ({ ...pending, [postId]: false }));
    }
  };

  const handleClosePopup = () => {
    setIsCommentPopupOpen(false);
    setSelectedPostId(null);
  };

  return (
    <>
      <div className={style.layout}>
        {loading ? <p>Đang tải bài viết...</p> : loadError ? (
          <div role="alert"><p>{loadError}</p><button onClick={() => fetchPosts()}>Thử lại</button></div>
        ) : data.length > 0 ? (
          data.map((post) => {
            const isExpanded = !!expandedPosts[post.id];
            const shouldShowToggle = (post.content || "").length > CONTENT_PREVIEW_LENGTH;

            return (
              <div key={post.id} className={style.box}>
                <div className={style["post-content"]}>
                  <div className={style.header_post}>
                    <Link to={`/user/${post.userId}`} className={style.avatar_mini_wrapper} aria-label={`Xem trang của ${post.username}`}>
                      <img
                        src={post.userAvatarUrl || DEFAULT_AVATAR_URL}
                        alt="User Avatar"
                        className={style.avatar}
                      />
                    </Link>
                    <div className={style.postMeta}>
                      <Link to={`/user/${post.userId}`} className={style["user-box"]} title={post.username}>
                        {post.username ? "@" + post.username : "Unknown user"}
                      </Link>
                      <Timestamp value={post.createdAt} className={style.postTime} />
                    </div>
                  </div>
                </div>

                <div
                  className={`${style["desc-box"]} ${
                    shouldShowToggle && !isExpanded ? style.collapsed : style.expanded
                  }`}
                >
                  {post.content}
                </div>

                {shouldShowToggle && (
                  <div className={style.readMoreWrapper}>
                    <button
                      type="button"
                      className={style.readMoreBtn}
                      onClick={() => toggleExpand(post.id)}
                      aria-expanded={isExpanded}
                    >
                      {isExpanded ? "See less" : "See more"}
                    </button>
                  </div>
                )}

                {Array.isArray(post.imageContentUrls) &&
                  post.imageContentUrls.map((url, index) => (
                    <img
                      key={index}
                      src={url}
                      className={style.picture}
                      alt={`Post image ${index}`}
                    />
                  ))}

                <div className={style["status-bar"]}>
                  <button
                    type="button"
                    className={`${style.btn} ${style["btn-like"]} ${
                      post.liked ? style["btn-like-active"] : ""
                    }`}
                    aria-label="Like"
                    aria-pressed={post.liked}
                    onClick={() => handleAction(post.id, "LIKE")}
                  >
                    <span className={style.icon} />
                  </button>
                  <span>{post.likesCount}</span>

                  <button
                    type="button"
                    className={`${style.btn} ${style["btn-comment"]}`}
                    aria-label="Comment"
                    onClick={() => handleAction(post.id, "COMMENT")}
                  >
                    <span className={style.icon} />
                  </button>
                  <span>{post.commentsCount}</span>

                  <button
                    type="button"
                    className={`${style.btn} ${style["btn-share"]}`}
                    aria-label="Share"
                    disabled={Boolean(sharing[post.id])}
                    onClick={() => handleAction(post.id, "SHARE")}
                  >
                    <span className={style.icon} />
                  </button>
                  <span>{post.sharesCount}</span>
                </div>
              </div>
            );
          })
        ) : (
          <center>
            <span>
              <i>Empty post</i>
            </span>
          </center>
        )}
      </div>

      {isCommentPopupOpen && (
        <CommentPopup
          postId={selectedPostId}
          onClose={handleClosePopup}
          onCommentAdded={(nextCommentsCount) =>
            handleCommentAdded(selectedPostId, nextCommentsCount)
          }
        />
      )}
    </>
  );
}
