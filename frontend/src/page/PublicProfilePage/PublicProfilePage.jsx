import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { toast } from "sonner";
import { ArrowLeft } from "lucide-react";
import style from "./PublicProfilePage.module.css";
import { assetUrl } from "../../config/assets";

export default function PublicProfilePage() {
  const { userId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [user, setUser] = useState(null);
  const [posts, setPosts] = useState([]);
  const [loading, setLoading] = useState(true);

  const isSearchMode = useMemo(
    () => location.pathname.startsWith("/search/"),
    [location.pathname]
  );

  useEffect(() => {
    const fetchProfile = async () => {
      try {
        setLoading(true);

        const [userResponse, postsResponse] = await Promise.all([
          axios.get(buildApiUrl(`/api/v1/users/${userId}`)),
          axios.get(buildApiUrl(`/api/v1/blogs/${userId}/blogs`)),
        ]);

        setUser(userResponse.data || null);
        setPosts(Array.isArray(postsResponse.data) ? postsResponse.data : []);
      } catch (error) {
        console.error("Cannot load public profile:", error);
        toast.error("Không thể tải trang cá nhân");
        setUser(null);
        setPosts([]);
      } finally {
        setLoading(false);
      }
    };

    if (userId) {
      fetchProfile();
    }
  }, [userId]);

  const handleBack = () => {
    if (isSearchMode) {
      navigate("/search");
      return;
    }

    navigate(-1);
  };

  if (loading) {
    return <div className={style.stateBox}>Đang tải trang cá nhân...</div>;
  }

  if (!user) {
    return <div className={style.stateBox}>Không tìm thấy người dùng.</div>;
  }

  return (
    <div className={style.page}>
      <div className={style.header}>
        <button
          type="button"
          className={style.backButton}
          onClick={handleBack}
        >
          <ArrowLeft size={18} />
        </button>
        <h1>Profile</h1>
      </div>

      <section className={style.profileCard}>
        <img
          src={user.userAvatarUrl || assetUrl("default-avatar.png")}
          alt={user.username || user.fullname || "User avatar"}
          className={style.avatar}
        />

        <div className={style.profileInfo}>
          <h2>{user.fullname || user.username || "Unknown user"}</h2>
          <span>@{user.username || "unknown"}</span>
          <p>{posts.length} public post{posts.length === 1 ? "" : "s"}</p>
        </div>
      </section>

      <section className={style.postsSection}>
        <div className={style.sectionTitle}>Bài đăng</div>

        {posts.length === 0 ? (
          <div className={style.emptyBox}>Người dùng này chưa có bài đăng công khai.</div>
        ) : (
          [...posts].reverse().map((post) => (
            <article key={post.id} className={style.postCard}>
              <div className={style.postHeader}>
                <img
                  src={post.userAvatarUrl || assetUrl("default-avatar.png")}
                  alt={post.username || "User avatar"}
                  className={style.postAvatar}
                />
                <div className={style.postMeta}>
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

              <div className={style.postContent}>{post.content}</div>

              {Array.isArray(post.imageContentUrls) &&
                post.imageContentUrls.map((url, index) => (
                  <img
                    key={`${post.id}-${index}`}
                    src={url}
                    alt={`Post ${index}`}
                    className={style.postImage}
                  />
                ))}

              <div className={style.postStats}>
                <span>Likes {post.likesCount || 0}</span>
                <span>Comments {post.commentsCount || 0}</span>
                <span>Shares {post.sharesCount || 0}</span>
              </div>
            </article>
          ))
        )}
      </section>
    </div>
  );
}