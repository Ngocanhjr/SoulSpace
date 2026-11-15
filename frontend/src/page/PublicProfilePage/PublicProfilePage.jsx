import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { ArrowLeft } from "lucide-react";
import style from "./PublicProfilePage.module.css";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import Timestamp from "../../Components/Timestamp";

export default function PublicProfilePage() {
  const { userId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [user, setUser] = useState(null);
  const [posts, setPosts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  const isSearchMode = useMemo(
    () => location.pathname.startsWith("/search/"),
    [location.pathname]
  );

  useEffect(() => {
    let active = true;
    const fetchProfile = async () => {
      try {
        setLoading(true);
        setLoadError("");

        const [userResponse, postsResponse] = await Promise.all([
          api.get(`/api/v1/users/${userId}`),
          api.get(`/api/v1/blogs/${userId}/blogs`),
        ]);
        if (!active) return;
        setUser(userResponse.data || null);
        setPosts(Array.isArray(postsResponse.data) ? postsResponse.data : []);
      } catch (error) {
        if (!active) return;
        setLoadError(apiErrorMessage(error, "Unable to load this profile."));
        setUser(null);
        setPosts([]);
      } finally {
        if (active) setLoading(false);
      }
    };

    if (userId) {
      fetchProfile();
    }
    return () => { active = false; };
  }, [userId]);

  const handleBack = () => {
    if (isSearchMode) {
      navigate("/search");
      return;
    }

    navigate(-1);
  };

  if (loading) {
    return <div className={style.stateBox}>Loading profile...</div>;
  }

  if (!user) {
    return <div role="alert" className={style.stateBox}>{loadError || "User not found."}</div>;
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
          src={user.userAvatarUrl || DEFAULT_AVATAR_URL}
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
        <div className={style.sectionTitle}>Posts</div>

        {posts.length === 0 ? (
          <div className={style.emptyBox}>This user has no public posts yet.</div>
        ) : (
          posts.map((post) => (
            <article key={post.id} className={style.postCard}>
              <div className={style.postHeader}>
                <img
                  src={post.userAvatarUrl || DEFAULT_AVATAR_URL}
                  alt={post.username || "User avatar"}
                  className={style.postAvatar}
                />
                <div className={style.postMeta}>
                  <strong>{post.username ? `@${post.username}` : "Unknown user"}</strong>
                  <Timestamp value={post.createdAt} />
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
