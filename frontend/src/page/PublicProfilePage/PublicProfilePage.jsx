import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { api, apiErrorMessage } from "../../config/api";
import { ArrowLeft } from "lucide-react";
import style from "./PublicProfilePage.module.css";
import { DEFAULT_AVATAR_URL } from "../../config/assets";
import ListPost from "../../Components/ListPost/ListPost";

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

        const userResponse = await api.get(`/api/v1/users/${userId}`);
        if (!active) return;
        setUser(userResponse.data || null);
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

        <ListPost
          key={userId}
          sourceUrl={`/api/v1/blogs/${userId}/blogs`}
          onPostsLoaded={setPosts}
        />
      </section>
    </div>
  );
}
