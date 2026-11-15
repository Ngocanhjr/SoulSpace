import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import axios from "axios";
import { buildApiUrl } from "../../config/api";
import { Search, X } from "lucide-react";
import style from "./SearchPage.module.css";
import { assetUrl } from "../../config/assets";

const PEOPLE_SUGGESTION_LIMIT = 4;

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
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState("");
  const [peopleError, setPeopleError] = useState("");
  const [needsLogin, setNeedsLogin] = useState(false);
  const activeRequest = useRef(null);
  const [isInputFocused, setIsInputFocused] = useState(false);

  useEffect(() => {
    const request = new AbortController();
    const fetchSearchSources = async () => {
      try {
        const response = await axios.get(buildApiUrl("/api/v1/users"), {
          signal: request.signal
        });
        if (!Array.isArray(response.data)) throw new Error("Invalid users response");
        setUsers(response.data);
      } catch (error) {
        if (!axios.isCancel(error)) setPeopleError("Chưa tải được danh sách người dùng.");
      }
    };

    fetchSearchSources();
    return () => request.abort();
  }, []);

  useEffect(() => () => activeRequest.current?.abort(), []);

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

  const peopleSuggestions = matchedPeople.slice(0, PEOPLE_SUGGESTION_LIMIT);

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

  const searchedPostResults = posts;

  const showSuggestions =
    !!normalizedQuery &&
    isInputFocused &&
    peopleSuggestions.length > 0;

  const handleSubmit = async (event) => {
    event.preventDefault();
    const safeQuery = query.trim();
    activeRequest.current?.abort();
    setPosts([]);
    setSearchError("");
    setNeedsLogin(false);
    setSearching(false);
    setSubmittedQuery(safeQuery);
    setIsInputFocused(false);
    if (!safeQuery) {
      return;
    }
    const request = new AbortController();
    activeRequest.current = request;
    setSearching(true);
    try {
      const response = await axios.get(buildApiUrl("/api/v1/search"), {
        params: { q: safeQuery },
        signal: request.signal
      });
      if (request.signal.aborted) return;
      if (!Array.isArray(response.data)) throw new Error("Invalid search response");
      setPosts(response.data);
    } catch (error) {
      if (request.signal.aborted) return;
      const status = error.response?.status;
      setNeedsLogin(status === 401);
      setSearchError(
        status === 401 ? "Bạn cần đăng nhập để tìm kiếm." :
        status === 400 ? "Câu tìm kiếm cần có từ 1 đến 2000 ký tự." :
        status === 503 ? "Dịch vụ tìm kiếm đang bận. Hãy thử lại." :
        "Không thể tìm kiếm lúc này."
      );
    } finally {
      if (activeRequest.current === request) setSearching(false);
    }
  };

  const handleClear = () => {
    activeRequest.current?.abort();
    activeRequest.current = null;
    setPosts([]);
    setSearching(false);
    setSearchError("");
    setNeedsLogin(false);
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
            maxLength={2000}
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

              </div>
            )}

            {!submittedQuery ? (
              <div className={style.emptyArea}>
                <p className={style.emptyTitle}>Chưa có nội dung tìm kiếm.</p>
                <span className={style.emptySubtitle}>
                  Nhập tên người dùng hoặc câu tìm kiếm, rồi nhấn Enter.
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
                  emptyMessage={peopleError || "Không tìm thấy người dùng phù hợp."}
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

                {searching && <p role="status">Đang tìm bài gần nghĩa...</p>}
                {searchError && <p role="alert">{searchError}</p>}
                {needsLogin && <a href={buildApiUrl("/login")}>Đăng nhập backend</a>}
                {!searching && !searchError && (
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
                )}
              </div>
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
