import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { toast } from "sonner";
import { Search, X } from "lucide-react";
import style from "./SearchPage.module.css";
import { assetUrl } from "../../config/assets";

const PEOPLE_SUGGESTION_LIMIT = 4;
const POST_SUGGESTION_LIMIT = 4;

function normalizeText(value) {
  return String(value || "").trim().toLowerCase();
}

function getPostPreview(content) {
  const safeContent = String(content || "").trim();
  if (safeContent.length <= 120) {
    return safeContent;
  }
  return `${safeContent.slice(0, 120)}...`;
}

export default function SearchPage() {
  const navigate = useNavigate();
  const [users, setUsers] = useState([]);
  const [posts, setPosts] = useState([]);
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [isInputFocused, setIsInputFocused] = useState(false);

  useEffect(() => {
    const fetchSearchSources = async () => {
      try {
        const [usersResponse, postsResponse] = await Promise.all([
          axios.get(buildApiUrl("/api/v1/users")),
          axios.get(buildApiUrl("/api/v1/soulspaces")),
        ]);

        setUsers(Array.isArray(usersResponse.data) ? usersResponse.data : []);
        setPosts(Array.isArray(postsResponse.data) ? postsResponse.data : []);
      } catch (error) {
        console.error("Cannot load search data:", error);
        toast.error("Không thể tải dữ liệu tìm kiếm");
      } finally {
        setLoading(false);
      }
    };

    fetchSearchSources();
  }, []);

  const normalizedQuery = useMemo(() => normalizeText(query), [query]);
  const normalizedSubmittedQuery = useMemo(
    () => normalizeText(submittedQuery),
    [submittedQuery]
  );

  const matchedPeople = useMemo(() => {
    if (!normalizedQuery) {
      return [];
    }

    return users.filter((user) => {
      const fullname = normalizeText(user.fullname);
      const username = normalizeText(user.username);
      return fullname.includes(normalizedQuery) || username.includes(normalizedQuery);
    });
  }, [users, normalizedQuery]);

  const matchedPosts = useMemo(() => {
    if (!normalizedQuery) {
      return [];
    }

    return posts.filter((post) => {
      const content = normalizeText(post.content);
      return content.includes(normalizedQuery);
    });
  }, [posts, normalizedQuery]);

  const peopleSuggestions = matchedPeople.slice(0, PEOPLE_SUGGESTION_LIMIT);
  const postSuggestions = matchedPosts.slice(0, POST_SUGGESTION_LIMIT);

  const searchedPeopleResults = useMemo(() => {
    if (!normalizedSubmittedQuery) {
      return [];
    }

    return users.filter((user) => {
      const fullname = normalizeText(user.fullname);
      const username = normalizeText(user.username);
      return (
        fullname.includes(normalizedSubmittedQuery) ||
        username.includes(normalizedSubmittedQuery)
      );
    });
  }, [users, normalizedSubmittedQuery]);

  const searchedPostResults = useMemo(() => {
    if (!normalizedSubmittedQuery) {
      return [];
    }

    return posts.filter((post) => {
      const content = normalizeText(post.content);
      return content.includes(normalizedSubmittedQuery);
    });
  }, [posts, normalizedSubmittedQuery]);

  const showSuggestions =
    !!normalizedQuery &&
    isInputFocused &&
    (peopleSuggestions.length > 0 || postSuggestions.length > 0);

  const handleSubmit = (event) => {
    event.preventDefault();
    const safeQuery = query.trim();

    if (!safeQuery) {
      setSubmittedQuery("");
      return;
    }

    setSubmittedQuery(safeQuery);
    setIsInputFocused(false);
  };

  const handleClear = () => {
    setQuery("");
    setSubmittedQuery("");
    setIsInputFocused(false);
  };

  const handleOpenProfile = (userId) => {
    navigate(`/search/user/${userId}`);
  };

  const handleOpenPost = (postId) => {
    navigate(`/search/post/${postId}`);
  };

  return (
    <div className={style.searchPage}>
      <div className={style.header}>
        <h1>Search</h1>
      </div>

      <form className={style.searchForm} onSubmit={handleSubmit}>
        <div className={style.searchInputWrapper}>
          <Search size={18} className={style.searchIcon} />
          <input
            type="text"
            value={query}
            placeholder="Tìm kiếm"
            className={style.searchInput}
            onChange={(event) => setQuery(event.target.value)}
            onFocus={() => setIsInputFocused(true)}
          />
          {query && (
            <button
              type="button"
              className={style.clearButton}
              onClick={handleClear}
              aria-label="Clear search"
            >
              <X size={16} />
            </button>
          )}
        </div>
      </form>

      <div className={style.body}>
        {loading ? (
          <div className={style.stateBox}>Đang tải dữ liệu tìm kiếm...</div>
        ) : (
          <>
            {showSuggestions && (
              <div className={style.suggestionsPanel}>
                <SearchSection
                  title="People"
                  count={peopleSuggestions.length}
                  emptyMessage="Không có gợi ý người dùng phù hợp."
                >
                  {peopleSuggestions.map((user) => (
                    <button
                      type="button"
                      key={user.id}
                      className={style.resultItem}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => handleOpenProfile(user.id)}
                    >
                      <img
                        src={user.userAvatarUrl || assetUrl("default-avatar.png")}
                        alt={user.username || user.fullname || "User avatar"}
                        className={style.avatar}
                      />
                      <div className={style.resultTextGroup}>
                        <strong>{user.fullname || user.username || "Unknown user"}</strong>
                        <span>@{user.username || "unknown"}</span>
                      </div>
                    </button>
                  ))}
                </SearchSection>

                <SearchSection
                  title="Posts"
                  count={postSuggestions.length}
                  emptyMessage="Không có gợi ý bài đăng phù hợp."
                >
                  {postSuggestions.map((post) => (
                    <button
                      type="button"
                      key={post.id}
                      className={style.resultItem}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => handleOpenPost(post.id)}
                    >
                      <img
                        src={
                          post.imageContentUrls?.[0] ||
                          post.userAvatarUrl ||
                          assetUrl("default-avatar.png")
                        }
                        alt={post.content || "Post preview"}
                        className={style.previewImage}
                      />
                      <div className={style.resultTextGroup}>
                        <strong>{post.username ? `@${post.username}` : "Unknown user"}</strong>
                        <span>{getPostPreview(post.content)}</span>
                      </div>
                    </button>
                  ))}
                </SearchSection>
              </div>
            )}

            {!submittedQuery ? (
              <div className={style.emptyArea}>
                <p className={style.emptyTitle}>Chưa có nội dung tìm kiếm.</p>
                <span className={style.emptySubtitle}>
                  Nhập tên người dùng hoặc nội dung bài đăng để nhận gợi ý.
                </span>
              </div>
            ) : (
              <div className={style.resultsPanel}>
                <div className={style.resultSummary}>
                  Kết quả cho từ khóa <strong>"{submittedQuery}"</strong>
                </div>

                <SearchSection
                  title="People"
                  count={searchedPeopleResults.length}
                  emptyMessage="Không tìm thấy người dùng phù hợp."
                >
                  {searchedPeopleResults.map((user) => (
                    <button
                      type="button"
                      key={user.id}
                      className={style.resultItem}
                      onClick={() => handleOpenProfile(user.id)}
                    >
                      <img
                        src={user.userAvatarUrl || assetUrl("default-avatar.png")}
                        alt={user.username || user.fullname || "User avatar"}
                        className={style.avatar}
                      />
                      <div className={style.resultTextGroup}>
                        <strong>{user.fullname || user.username || "Unknown user"}</strong>
                        <span>@{user.username || "unknown"}</span>
                      </div>
                    </button>
                  ))}
                </SearchSection>

                <SearchSection
                  title="Posts"
                  count={searchedPostResults.length}
                  emptyMessage="Không tìm thấy bài đăng phù hợp."
                >
                  {searchedPostResults.map((post) => (
                    <button
                      type="button"
                      key={post.id}
                      className={style.resultItem}
                      onClick={() => handleOpenPost(post.id)}
                    >
                      <img
                        src={
                          post.imageContentUrls?.[0] ||
                          post.userAvatarUrl ||
                          assetUrl("default-avatar.png")
                        }
                        alt={post.content || "Post preview"}
                        className={style.previewImage}
                      />
                      <div className={style.resultTextGroup}>
                        <strong>{post.username ? `@${post.username}` : "Unknown user"}</strong>
                        <span>{getPostPreview(post.content)}</span>
                      </div>
                    </button>
                  ))}
                </SearchSection>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function SearchSection({ title, count, children, emptyMessage }) {
  const hasContent = Array.isArray(children)
    ? children.length > 0
    : Boolean(children);

  return (
    <section className={style.section}>
      <div className={style.sectionHeader}>
        <h2>{title}</h2>
        <span>{count}</span>
      </div>

      {hasContent ? children : <div className={style.emptySection}>{emptyMessage}</div>}
    </section>
  );
}