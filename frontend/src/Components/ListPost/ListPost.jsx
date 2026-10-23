import { useState, useEffect } from "react";
import style from "./ListPost.module.css";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { toast } from "sonner";
import CommentPopup from "../Comment/CommentPopup.jsx";
import { assetUrl } from "../../config/assets";

export default function ListPost() {
  const [data, setData] = useState([]);
  const [isCommentPopupOpen, setIsCommentPopupOpen] = useState(false);
  const [selectedPostId, setSelectedPostId] = useState(null);
  const [expandedPosts, setExpandedPosts] = useState({});

  const currentUserId = String(localStorage.getItem("userId") || "").replaceAll(
    '"',
    ""
  );

  const fetchPosts = async () => {
    try {
      const rs = await axios.get(buildApiUrl("/api/v1/soulspaces"));

      if (!Array.isArray(rs.data)) {
        console.error("API không trả về mảng hợp lệ:", rs.data);
        toast.error("Invalid API response format");
        return;
      }

      const processedData = rs.data.map((post) => ({
        ...post,
        liked:
          Array.isArray(post.likedUserIds) && currentUserId
            ? post.likedUserIds.includes(currentUserId)
            : false,
        likesCount: post.likesCount || 0,
        commentsCount: post.commentsCount || 0,
        sharesCount: post.sharesCount || 0,
      }));

      setData(processedData);
    } catch (error) {
      toast.error("Error connecting to database");
      console.error("Error fetching posts:", error);
    }
  };

  useEffect(() => {
    fetchPosts();
  }, []);

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
    if (type === "LIKE") {
      if (!currentUserId) {
        toast.error("Please sign in to like posts.");
        return;
      }

      try {
        const response = await axios.post(
          buildApiUrl(`/api/v1/blogs/${postId}/like`),
          {
            userId: currentUserId,
          }
        );

        setData((prevData) =>
          prevData.map((post) =>
            post.id === postId
              ? {
                  ...post,
                  liked: !!response.data?.liked,
                  likesCount:
                    typeof response.data?.likesCount === "number"
                      ? response.data.likesCount
                      : post.likesCount,
                }
              : post
          )
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
      setSelectedPostId(postId);
      setIsCommentPopupOpen(true);
    }

    if (type === "SHARE") {
      const userId = String(localStorage.getItem("userId") || "").replaceAll(
        '"',
        ""
      );

      if (!userId) {
        toast.error("Please sign in to share posts.");
        return;
      }

      try {
        const response = await axios.post(
          buildApiUrl(`/api/v1/blogs/${postId}/share`),
          { userId }
        );

        const nextSharesCount = response?.data?.sharesCount;

        setData((prevData) =>
          prevData.map((post) =>
            post.id === postId
              ? {
                  ...post,
                  sharesCount:
                    typeof nextSharesCount === "number"
                      ? nextSharesCount
                      : (post.sharesCount || 0) + 1,
                }
              : post
          )
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

  const handleClosePopup = () => {
    setIsCommentPopupOpen(false);
    setSelectedPostId(null);
  };

  return (
    <>
      <div className={style.layout}>
        {data.length > 0 ? (
          [...data].reverse().map((post) => {
            const isExpanded = !!expandedPosts[post.id];
            const shouldShowToggle = (post.content || "").length > 220;

            return (
              <div key={post.id} className={style.box}>
                <div className={style["post-content"]}>
                  <div className={style.header_post}>
                    <div className={style.avatar_mini_wrapper}>
                      <img
                        src={post.userAvatarUrl || assetUrl("default-avatar.png")}
                        alt="User Avatar"
                        className={style.avatar}
                      />
                    </div>
                    <div className={style["user-box"]}>
                      {post.username ? "@" + post.username : "Unknown user"}
                    </div>
                  </div>
                </div>

                <div
                  className={`${style["desc-box"]} ${
                    isExpanded ? style.expanded : style.collapsed
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

                <div className={style.timeWrapper}>
                  <span className={style.postTime}>
                    {new Date(post.createdAt).toLocaleString("vi-VN", {
                      hour12: false,
                    })}
                  </span>
                </div>

                <div className={style["status-bar"]}>
                  <button
                    type="button"
                    className={`${style.btn} ${style["btn-like"]} ${
                      post.liked ? style["btn-like-active"] : ""
                    }`}
                    aria-label="Like"
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