import { useState, useEffect, useCallback, useRef } from "react";
import style from "./ListPost.module.css";
import { toast } from "sonner";
import CommentPopup from "../Comment/CommentPopup.jsx";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import Timestamp from "../Timestamp";
import { Link } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { useAuth } from "../../config/auth";
import { useLikes } from "../../config/useLikes";
import { toggleShare } from "../../config/shares";
import { Repeat2 } from "lucide-react";
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
  const shareRequests = useRef(new Set());
  const shareActor = useRef(currentUserId);
  useEffect(() => { shareActor.current = currentUserId; }, [currentUserId]);
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
      setLoadError(apiErrorMessage(error, "Unable to load posts."));
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
      toast.error("Please sign in to interact.");
      return;
    }
    if (type === "LIKE") {
      const post = data.find((item) => item.id === postId);
      if (post) likes.toggle("/api/v1/blogs/" + postId + "/like", post.liked, post.likesCount);
      return;
    }
    const post = data.find((item) => item.id === postId);
    if (!post || shareRequests.current.has(postId)) return;
    shareRequests.current.add(postId);
    setSharing((pending) => ({ ...pending, [postId]: true }));
    try {
      const result = await toggleShare(post, currentUserId, (next) => {
        if (shareActor.current === currentUserId) {
          setData((rows) => rows.map((row) => row.id === postId ? { ...row, ...next } : row));
        }
      });
      if (shareActor.current !== currentUserId) return;
      if (result.unavailable) setData((rows) => rows.filter((row) => row.id !== postId));
      if (result.warning) toast.warning(result.message);
      else toast.success(result.message);
    } catch (error) {
      if (shareActor.current !== currentUserId) return;
      if (error.response?.status === 404) setData((rows) => rows.filter((row) => row.id !== postId));
      toast.error(apiErrorMessage(error, "Unable to share this post."));
    } finally {
      shareRequests.current.delete(postId);
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
        {loading ? <p>Loading posts...</p> : loadError ? (
          <div role="alert"><p>{loadError}</p><button onClick={() => fetchPosts()}>Try again</button></div>
        ) : data.length > 0 ? (
          data.map((post) => {
            const isExpanded = !!expandedPosts[post.id];
            const shouldShowToggle = (post.content || "").length > CONTENT_PREVIEW_LENGTH;

            return (
              <div key={post.id} className={style.box}>
                <div className={style["post-content"]}>
                  <div className={style.header_post}>
                    <Link to={`/user/${post.userId}`} className={style.avatar_mini_wrapper} aria-label={`View profile of ${post.username}`}>
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
                    aria-label={post.liked ? "Unlike" : "Like"}
                    title={post.liked ? "Unlike" : "Like"}
                    aria-pressed={post.liked}
                    onClick={() => handleAction(post.id, "LIKE")}
                  >
                    <span className={style.icon} />
                  </button>
                  <span>{post.likesCount}</span>

                  <button
                    type="button"
                    className={`${style.btn} ${style["btn-comment"]}`}
                    aria-label="Comments"
                    title="Comments"
                    onClick={() => handleAction(post.id, "COMMENT")}
                  >
                    <span className={style.icon} />
                  </button>
                  <span>{post.commentsCount}</span>

                  <button
                    type="button"
                    className={`${style.btn} ${style["btn-share"]} ${post.sharedByMe ? style["btn-share-active"] : ""}`}
                    aria-label={post.sharedByMe ? "Remove share" : "Share"}
                    aria-pressed={Boolean(post.sharedByMe)}
                    aria-busy={Boolean(sharing[post.id])}
                    title={post.sharedByMe ? "Shared · Click to remove" : "Share"}
                    disabled={Boolean(sharing[post.id])}
                    onClick={() => handleAction(post.id, "SHARE")}
                  >
                    <Repeat2 className={style.icon} aria-hidden="true" />
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
